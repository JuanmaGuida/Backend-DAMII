package com.reclamos.backend.service;

import com.reclamos.backend.config.DuplicateSearchProperties;
import com.reclamos.backend.dto.response.DuplicateCandidateResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Neighborhood;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DuplicateCandidateService {
    static final Set<TicketStatus> CANDIDATE_STATUSES = Set.of(
            TicketStatus.REGISTERED,
            TicketStatus.IN_REVIEW,
            TicketStatus.ROUTED,
            TicketStatus.IN_PROGRESS,
            TicketStatus.PENDING_INFORMATION
    );

    private final TicketRepository ticketRepository;
    private final TicketLocationRepository ticketLocationRepository;
    private final DuplicateSearchProperties properties;
    private final HaversineDistanceCalculator distanceCalculator;
    private final TicketService ticketService;

    @Transactional(readOnly = true)
    public List<DuplicateCandidateResponse> findCandidates(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket source = ticketRepository.findWithClassificationById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        ticketService.requireTriageAuthority(source, identity);
        TicketLocation sourceLocation = ticketLocationRepository.findByTicket_Id(ticketId).orElse(null);
        if (!hasCoordinates(sourceLocation)) {
            return List.of();
        }

        Category category = source.getRequestType().getSubcategory().getCategory();
        Instant from = source.getCreatedAt().minus(properties.getTimeWindow());
        Instant to = source.getCreatedAt().plus(properties.getTimeWindow());

        return ticketLocationRepository.findDuplicateCandidates(
                        ticketId, category.getId(), from, to, CANDIDATE_STATUSES).stream()
                .map(location -> mapCandidate(source, sourceLocation, location))
                .filter(candidate -> candidate.distanceMeters() <= properties.getRadiusMeters())
                .sorted(candidateComparator())
                .map(Candidate::response)
                .toList();
    }

    private Candidate mapCandidate(Ticket source, TicketLocation sourceLocation, TicketLocation candidateLocation) {
        Ticket candidate = candidateLocation.getTicket();
        RequestType requestType = candidate.getRequestType();
        Category category = requestType.getSubcategory().getCategory();
        Neighborhood neighborhood = candidateLocation.getNeighborhood();
        double distance = distanceCalculator.distanceMeters(
                sourceLocation.getLatitude(), sourceLocation.getLongitude(),
                candidateLocation.getLatitude(), candidateLocation.getLongitude());
        Duration timeDifference = Duration.between(source.getCreatedAt(), candidate.getCreatedAt()).abs();

        DuplicateCandidateResponse response = new DuplicateCandidateResponse(
                candidate.getId(), candidate.getPublicId(), candidate.getSummary(), candidate.getCurrentStatus(),
                category.getId(), category.getName(), requestType.getCode(), requestType.getName(),
                neighborhood == null ? null : neighborhood.getId(),
                neighborhood == null ? null : neighborhood.getName(),
                distance, timeDifference.toMinutes(), candidate.getCreatedAt());
        return new Candidate(response, timeDifference);
    }

    private Comparator<Candidate> candidateComparator() {
        return Comparator.comparingDouble((Candidate candidate) -> candidate.response().distanceMeters())
                .thenComparing(Candidate::timeDifference)
                .thenComparing(candidate -> candidate.response().createdAt())
                .thenComparing(candidate -> candidate.response().publicId(), Comparator.nullsLast(String::compareTo))
                .thenComparing(candidate -> candidate.response().ticketId());
    }

    private boolean hasCoordinates(TicketLocation location) {
        return location != null && location.getLatitude() != null && location.getLongitude() != null;
    }

    private record Candidate(DuplicateCandidateResponse response, Duration timeDifference) {
        private double distanceMeters() {
            return response.distanceMeters();
        }
    }
}