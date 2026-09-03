package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class TicketService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RequestTypeRepository requestTypeRepository;
    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketLocationRepository locationRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final FormValidationService formValidationService;
    private final RiskCalculationService riskCalculationService;
    private final TrackingCodeService trackingCodeService;

    @Transactional
    public CreateTicketResponse create(CreateTicketRequest request, AuthenticatedIdentity identity,
                                       MultipartFile[] evidence) {
        if (identity == null) {
            throw new InvalidTicketRequestException("Se requiere la identidad del ciudadano");
        }
        RequestType requestType = requestTypeRepository.findById(request.requestTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Request Type no encontrado"));
        if (!requestType.isActive()) {
            throw new InvalidTicketRequestException("El Request Type seleccionado está inactivo");
        }
        var fields = formValidationService.validateAndGetFields(requestType, request.formData());
        Risk risk = riskCalculationService.calculateRisk(requestType, request.formData(), fields);
        // TODO Attachment: evidence is only checked for presence in this US. Persist it when storage exists.
        boolean validEvidence = evidence != null && java.util.Arrays.stream(evidence)
                .anyMatch(file -> file != null && !file.isEmpty() && file.getSize() > 0);
        if ((risk == Risk.HIGH || risk == Risk.CRITICAL) && !validEvidence) {
            throw new EvidenceRequiredException();
        }
        validateLocation(requestType, request.location());

        String trackingCode;
        String trackingHash;
        do {
            trackingCode = trackingCodeService.generate();
            trackingHash = trackingCodeService.hash(trackingCode);
        } while (ticketRepository.existsByTrackingCodeHash(trackingHash));

        String publicId;
        do {
            publicId = generatePublicId();
        } while (ticketRepository.existsByPublicId(publicId));

        Instant now = Instant.now();
        Ticket ticket = new Ticket();
        ticket.setPublicId(publicId);
        ticket.setTrackingCodeHash(trackingHash);
        ticket.setCitizenId(identity.citizenId());
        ticket.setAnonymous(false);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary(request.summary());
        ticket.setDescription(request.description());
        ticket.setFormData(new HashMap<>(request.formData()));
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        ticket.setCurrentPriority(max(requestType.getMinimumPriority(), risk));
        ticket.setEstimatedAffectedCount(0);
        ticket.setReopenCount(0);
        ticket.setEscalated(false);
        ticket.setPublic(false);
        ticket.setStatusChangedAt(now);
        ticket = ticketRepository.save(ticket);

        if (request.location() != null) {
            locationRepository.save(toLocation(ticket, request.location()));
        }
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(1);
        activity.setActionType(ActivityType.TICKET_CREATED);
        activity.setPreviousStatus(null);
        activity.setNewStatus(TicketStatus.REGISTERED);
        activity.setActorType(ActorType.CITIZEN);
        activity.setActorId(identity.subjectId());
        activity.setOccurredAt(now);
        activityRepository.save(activity);
        return new CreateTicketResponse(ticket.getId(), ticket.getPublicId(), trackingCode,
                TicketStatus.REGISTERED);
    }

    private void validateLocation(RequestType type, CreateTicketRequest.LocationData location) {
        if (type.isRequiresLocation() && location == null) {
            throw new InvalidTicketRequestException("La ubicación es obligatoria para el Request Type seleccionado");
        }
        if (location == null) return;
        boolean hasLatitude = location.latitude() != null;
        boolean hasLongitude = location.longitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new InvalidTicketRequestException("La latitud y longitud deben informarse juntas");
        }
        boolean hasCoordinates = hasLatitude;
        boolean hasUsefulLocation = location.addressLine() != null && !location.addressLine().isBlank()
                || location.neighborhoodId() != null || hasCoordinates;
        if (type.isRequiresLocation() && !hasUsefulLocation) {
            throw new InvalidTicketRequestException("La ubicación es obligatoria para el Request Type seleccionado");
        }
        if (hasCoordinates && (location.latitude().doubleValue() < -90 || location.latitude().doubleValue() > 90
                || location.longitude().doubleValue() < -180 || location.longitude().doubleValue() > 180)) {
            throw new InvalidTicketRequestException("Las coordenadas de ubicación son inválidas");
        }
        if (location.neighborhoodId() != null && !neighborhoodRepository.existsById(location.neighborhoodId())) {
            throw new ResourceNotFoundException("Barrio no encontrado");
        }
    }

    private TicketLocation toLocation(Ticket ticket, CreateTicketRequest.LocationData data) {
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setAddressLine(data.addressLine());
        location.setStreet(data.street());
        location.setStreetNumber(data.streetNumber());
        location.setLatitude(data.latitude());
        location.setLongitude(data.longitude());
        location.setReference(data.reference());
        if (data.neighborhoodId() != null) {
            location.setNeighborhood(neighborhoodRepository.getReferenceById(data.neighborhoodId()));
        }
        return location;
    }

    private Priority max(Priority minimum, Risk risk) {
        Priority fromRisk = Priority.valueOf(risk.name());
        return minimum.ordinal() >= fromRisk.ordinal() ? minimum : fromRisk;
    }

    private String generatePublicId() {
        StringBuilder value = new StringBuilder("OP-");
        for (int index = 0; index < 10; index++) {
            value.append(SECURE_RANDOM.nextInt(10));
        }
        return value.toString();
    }
}