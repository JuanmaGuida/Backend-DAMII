package com.reclamos.backend.service;

import com.reclamos.backend.config.DuplicateSearchProperties;
import com.reclamos.backend.dto.response.DuplicateCandidateResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Neighborhood;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuplicateCandidateServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-18T12:00:00Z");
    private static final AuthenticatedIdentity AGENT = new AuthenticatedIdentity(
            "agent", UUID.randomUUID(), "Agent", null, ModuleRole.AGENT);

    @Mock TicketRepository ticketRepository;
    @Mock TicketLocationRepository locationRepository;
    @Mock HaversineDistanceCalculator distanceCalculator;
    @Mock TicketService ticketService;

    private DuplicateSearchProperties properties;
    private DuplicateCandidateService service;
    private Ticket source;
    private TicketLocation sourceLocation;

    @BeforeEach
    void setUp() {
        properties = new DuplicateSearchProperties();
        properties.setRadiusMeters(500);
        properties.setTimeWindow(Duration.ofHours(24));
        service = new DuplicateCandidateService(
                ticketRepository, locationRepository, properties, distanceCalculator, ticketService);
        source = ticket(UUID.randomUUID(), "TK-2026-000001", 10L, "Categoría", 100L,
                "SOURCE", CREATED_AT, TicketStatus.IN_REVIEW);
        sourceLocation = location(source, "-34.603722", "-58.381592");
        lenient().when(ticketRepository.findWithClassificationById(source.getId())).thenReturn(Optional.of(source));
        lenient().when(locationRepository.findByTicket_Id(source.getId())).thenReturn(Optional.of(sourceLocation));
    }

    @Test
    void returnsCandidateWithDifferentRequestTypeAndSubcategoryFromSameCategory() {
        Ticket candidate = ticket(UUID.randomUUID(), "TK-2026-000002", 10L, "Categoría", 999L,
                "OTHER_REQUEST", CREATED_AT.plus(Duration.ofMinutes(15)), TicketStatus.REGISTERED);
        TicketLocation candidateLocation = location(candidate, "-34.604000", "-58.381592");
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Centro");
        candidateLocation.setNeighborhood(neighborhood);
        preliminaryCandidates(candidateLocation);
        when(distanceCalculator.distanceMeters(any(), any(), any(), any())).thenReturn(30.5);

        List<DuplicateCandidateResponse> result = service.findCandidates(source.getId(), AGENT);

        assertEquals(1, result.size());
        assertEquals(candidate.getId(), result.getFirst().ticketId());
        assertEquals(10L, result.getFirst().categoryId());
        assertEquals("OTHER_REQUEST", result.getFirst().requestTypeCode());
        assertEquals(15, result.getFirst().timeDifferenceMinutes());
        assertEquals(neighborhood.getId(), result.getFirst().neighborhoodId());
        verify(locationRepository).findDuplicateCandidates(source.getId(), 10L,
                CREATED_AT.minus(Duration.ofHours(24)), CREATED_AT.plus(Duration.ofHours(24)),
                DuplicateCandidateService.CANDIDATE_STATUSES);
    }

    @Test
    void includesExactRadiusAndFiltersAnythingBeyondItWithoutRounding() {
        Ticket atLimit = ticket(UUID.randomUUID(), "TK-2026-000002", 10L, "Categoría", 101L,
                "AT_LIMIT", CREATED_AT, TicketStatus.ROUTED);
        Ticket outside = ticket(UUID.randomUUID(), "TK-2026-000003", 10L, "Categoría", 102L,
                "OUTSIDE", CREATED_AT, TicketStatus.IN_PROGRESS);
        preliminaryCandidates(location(atLimit, "1", "1"), location(outside, "2", "2"));
        when(distanceCalculator.distanceMeters(any(), any(), any(), any())).thenReturn(500.0, 500.000001);

        List<DuplicateCandidateResponse> result = service.findCandidates(source.getId(), AGENT);

        assertEquals(List.of(atLimit.getId()), result.stream().map(DuplicateCandidateResponse::ticketId).toList());
    }

    @Test
    void configuredRadiusChangesResultForSameDataset() {
        Ticket candidate = ticket(UUID.randomUUID(), "TK-2026-000002", 10L, "Categoría", 101L,
                "CANDIDATE", CREATED_AT, TicketStatus.PENDING_INFORMATION);
        TicketLocation location = location(candidate, "1", "1");
        preliminaryCandidates(location);
        when(distanceCalculator.distanceMeters(any(), any(), any(), any())).thenReturn(600.0);
        assertEquals(List.of(), service.findCandidates(source.getId(), AGENT));

        properties.setRadiusMeters(700);
        preliminaryCandidates(location);
        assertEquals(1, service.findCandidates(source.getId(), AGENT).size());
    }

    @Test
    void configuredWindowIsAppliedInclusivelyAroundSourceCreation() {
        properties.setTimeWindow(Duration.ofHours(2));
        preliminaryCandidates();

        service.findCandidates(source.getId(), AGENT);

        verify(locationRepository).findDuplicateCandidates(source.getId(), 10L,
                CREATED_AT.minus(Duration.ofHours(2)), CREATED_AT.plus(Duration.ofHours(2)),
                DuplicateCandidateService.CANDIDATE_STATUSES);
    }

    @Test
    void ordersByDistanceThenExactTimeDifferenceThenCreationAndId() {
        Ticket farther = ticket(UUID.randomUUID(), "TK-2026-000004", 10L, "Categoría", 104L,
                "FARTHER", CREATED_AT.plusSeconds(1), TicketStatus.REGISTERED);
        Ticket later = ticket(UUID.randomUUID(), "TK-2026-000003", 10L, "Categoría", 103L,
                "LATER", CREATED_AT.plusSeconds(80), TicketStatus.REGISTERED);
        Ticket nearer = ticket(UUID.randomUUID(), "TK-2026-000002", 10L, "Categoría", 102L,
                "NEARER", CREATED_AT.plusSeconds(20), TicketStatus.REGISTERED);
        preliminaryCandidates(location(farther, "1", "1"), location(later, "2", "2"),
                location(nearer, "3", "3"));
        when(distanceCalculator.distanceMeters(any(), any(), any(), any())).thenReturn(20.0, 10.0, 10.0);

        List<UUID> ids = service.findCandidates(source.getId(), AGENT).stream()
                .map(DuplicateCandidateResponse::ticketId).toList();

        assertEquals(List.of(nearer.getId(), later.getId(), farther.getId()), ids);
    }

    @Test
    void missingOrIncompleteSourceLocationReturnsEmptyWithoutCandidateQuery() {
        when(locationRepository.findByTicket_Id(source.getId())).thenReturn(Optional.empty());
        assertEquals(List.of(), service.findCandidates(source.getId(), AGENT));

        sourceLocation.setLongitude(null);
        when(locationRepository.findByTicket_Id(source.getId())).thenReturn(Optional.of(sourceLocation));
        assertEquals(List.of(), service.findCandidates(source.getId(), AGENT));
        verify(locationRepository, never()).findDuplicateCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void emptyPreselectionReturnsNormalEmptyList() {
        preliminaryCandidates();
        assertEquals(List.of(), service.findCandidates(source.getId(), AGENT));
    }

    @Test
    void delegatesContextualTriageAuthorizationAndMissingSourceUsesStandardNotFound() {
        preliminaryCandidates();
        service.findCandidates(source.getId(), AGENT);
        verify(ticketService).requireTriageAuthority(source, AGENT);

        UUID missingId = UUID.randomUUID();
        when(ticketRepository.findWithClassificationById(missingId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.findCandidates(missingId, AGENT));
    }

    @Test
    void contextualAuthorizationFailureStopsSearchBeforeLoadingLocation() {
        doThrow(new UnauthorizedTicketOperationException())
                .when(ticketService).requireTriageAuthority(source, AGENT);

        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.findCandidates(source.getId(), AGENT));

        verify(locationRepository, never()).findByTicket_Id(any());
    }

    @Test
    void repositoryPreselectionReceivesOnlyStatusesEligibleAsMainTicket() {
        preliminaryCandidates();
        service.findCandidates(source.getId(), AGENT);

        assertEquals(Set.of(TicketStatus.REGISTERED, TicketStatus.IN_REVIEW, TicketStatus.ROUTED,
                        TicketStatus.IN_PROGRESS, TicketStatus.PENDING_INFORMATION),
                DuplicateCandidateService.CANDIDATE_STATUSES);
    }

    private void preliminaryCandidates(TicketLocation... locations) {
        when(locationRepository.findDuplicateCandidates(eq(source.getId()), eq(10L), any(), any(), any()))
                .thenReturn(List.of(locations));
    }

    private Ticket ticket(UUID id, String publicId, long categoryId, String categoryName, long subcategoryId,
                          String requestTypeCode, Instant createdAt, TicketStatus status) {
        Category category = new Category();
        category.setId(categoryId);
        category.setName(categoryName);
        Subcategory subcategory = new Subcategory();
        subcategory.setId(subcategoryId);
        subcategory.setCategory(category);
        RequestType requestType = new RequestType();
        requestType.setId(subcategoryId);
        requestType.setCode(requestTypeCode);
        requestType.setName(requestTypeCode + " name");
        requestType.setSubcategory(subcategory);
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setPublicId(publicId);
        ticket.setSummary("Resumen " + publicId);
        ticket.setRequestType(requestType);
        ticket.setCreatedAt(createdAt);
        ticket.setCurrentStatus(status);
        return ticket;
    }

    private TicketLocation location(Ticket ticket, String latitude, String longitude) {
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setLatitude(new BigDecimal(latitude));
        location.setLongitude(new BigDecimal(longitude));
        return location;
    }
}