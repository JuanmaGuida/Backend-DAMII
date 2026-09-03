package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TicketServiceTest {
    private final RequestTypeRepository requestTypes = mock(RequestTypeRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final TicketLocationRepository locations = mock(TicketLocationRepository.class);
    private final NeighborhoodRepository neighborhoods = mock(NeighborhoodRepository.class);
    private final FormValidationService forms = mock(FormValidationService.class);
    private final RiskCalculationService risks = mock(RiskCalculationService.class);
    private final TrackingCodeService trackingCodes = new TrackingCodeService();
    private TicketService service;
    private RequestType requestType;

    @BeforeEach
    void setUp() {
        reset(requestTypes, tickets, activities, locations, neighborhoods, forms, risks);
        service = new TicketService(requestTypes, tickets, activities, locations, neighborhoods, forms, risks,
                trackingCodes);
        requestType = requestType(true);
        when(requestTypes.findById(1L)).thenReturn(Optional.of(requestType));
        when(tickets.save(any())).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(UUID.randomUUID());
            return ticket;
        });
    }

    @Test
    void lowRiskWithoutEvidenceCreatesRegisteredServerClassifiedTicket() {
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.LOW);
        CreateTicketResponse response = service.create(request(), identity(), null);

        assertEquals(TicketStatus.REGISTERED, response.status());
        assertNotNull(response.trackingCode());
        assertTrue(response.publicId().matches("OP-[0-9]{10}"));
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(response.publicId()));
        verify(tickets).save(argThat(ticket -> ticket.getCurrentStatus() == TicketStatus.REGISTERED
                && ticket.getTicketType() == requestType.getTicketType()
                && ticket.getResponsibleAreaId().equals(requestType.getResponsibleAreaId())
                && ticket.getRequestType().getSubcategory().getCategory() != null
                && !ticket.getTrackingCodeHash().equals(response.trackingCode())));
        verify(activities).save(argThat(activity -> activity.getActionType() == ActivityType.TICKET_CREATED
                && activity.getSequence() == 1));
    }

    @Test
    void mediumRiskWithoutEvidenceCreatesTicket() {
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.MEDIUM);
        assertNotNull(service.create(request(), identity(), null).ticketId());
    }

    @Test
    void highAndCriticalRiskWithEvidenceCreateTickets() {
        MockMultipartFile evidence = new MockMultipartFile("evidence", "photo.jpg", "image/jpeg", new byte[]{1});
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.HIGH, Risk.CRITICAL);
        assertNotNull(service.create(request(), identity(), new MockMultipartFile[]{evidence}).ticketId());
        assertNotNull(service.create(request(), identity(), new MockMultipartFile[]{evidence}).ticketId());
    }

    @Test
    void highAndCriticalRiskWithoutEvidenceDoNotPersist() {
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.HIGH, Risk.CRITICAL);
        assertThrows(EvidenceRequiredException.class, () -> service.create(request(), identity(), null));
        assertThrows(EvidenceRequiredException.class, () -> service.create(request(), identity(), null));
        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
        verify(locations, never()).save(any());
    }

    @Test
    void missingInvalidAndInactiveRequestTypesDoNotPersist() {
        when(requestTypes.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.create(new CreateTicketRequest(99L, "s", "d", Map.of(), null), identity(), null));
        requestType.setActive(false);
        assertThrows(InvalidTicketRequestException.class, () -> service.create(request(), identity(), null));
        verify(tickets, never()).save(any());
    }

    @Test
    void validationFailureDoesNotPersist() {
        when(forms.validateAndGetFields(any(), any())).thenThrow(new FormValidationException("required"));
        assertThrows(FormValidationException.class, () -> service.create(request(), identity(), null));
        verify(tickets, never()).save(any());
    }

    @Test
    void trackingCodesAreDifferentAndOnlyTheirHashesAreStored() {
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.LOW);
        CreateTicketResponse first = service.create(request(), identity(), null);
        CreateTicketResponse second = service.create(request(), identity(), null);
        assertNotEquals(first.trackingCode(), second.trackingCode());
        verify(tickets, times(2)).save(argThat(ticket -> !ticket.getTrackingCodeHash().equals(first.trackingCode())
                && !ticket.getTrackingCodeHash().equals(second.trackingCode())));
    }

    @Test
    void createdTrackingCodeImmediatelyFindsTheSameTicket() {
        allowLowRisk();
        CreateTicketResponse created = service.create(request(), identity(), null);
        var savedTicket = org.mockito.ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(savedTicket.capture());
        Ticket ticket = savedTicket.getValue();
        ticket.setCreatedAt(java.time.Instant.parse("2026-09-02T18:00:00Z"));
        when(tickets.findByTrackingCodeHash(trackingCodes.hash(created.trackingCode())))
                .thenReturn(Optional.of(ticket));

        var tracked = new TrackingService(tickets, trackingCodes).findByTrackingCode(created.trackingCode());

        assertEquals(created.publicId(), tracked.getPublicId());
        assertEquals(created.status(), tracked.getCurrentStatus());
    }


    @Test
    void minimumPriorityIsAlwaysAppliedAsFloor() {
        requestType.setMinimumPriority(Priority.HIGH);
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.LOW);
        service.create(request(), identity(), null);
        verify(tickets).save(argThat(ticket -> ticket.getCurrentPriority() == Priority.HIGH));
    }

    @Test
    void requiredLocationRejectsNullAndEmptyObjects() {
        requestType.setRequiresLocation(true);
        allowLowRisk();

        assertThrows(InvalidTicketRequestException.class, () -> service.create(request(), identity(), null));
        assertThrows(InvalidTicketRequestException.class,
                () -> service.create(request(location(null, null, null, null)), identity(), null));
        verify(tickets, never()).save(any());
    }

    @Test
    void locationRejectsLatitudeOrLongitudeWhenProvidedAlone() {
        allowLowRisk();
        InvalidTicketRequestException latitudeError = assertThrows(InvalidTicketRequestException.class,
                () -> service.create(request(location(null, BigDecimal.ZERO, null, null)), identity(), null));
        InvalidTicketRequestException longitudeError = assertThrows(InvalidTicketRequestException.class,
                () -> service.create(request(location(null, null, BigDecimal.ZERO, null)), identity(), null));

        assertEquals("La latitud y longitud deben informarse juntas", latitudeError.getMessage());
        assertEquals("La latitud y longitud deben informarse juntas", longitudeError.getMessage());
        verify(tickets, never()).save(any());
    }

    @Test
    void locationRejectsCoordinatesOutsideTheirRanges() {
        allowLowRisk();
        assertThrows(InvalidTicketRequestException.class,
                () -> service.create(request(location(null, new BigDecimal("90.1"), BigDecimal.ZERO, null)),
                        identity(), null));
        assertThrows(InvalidTicketRequestException.class,
                () -> service.create(request(location(null, BigDecimal.ZERO, new BigDecimal("180.1"), null)),
                        identity(), null));
        verify(tickets, never()).save(any());
    }

    @Test
    void locationRejectsUnknownNeighborhood() {
        allowLowRisk();
        UUID neighborhoodId = UUID.randomUUID();
        when(neighborhoods.existsById(neighborhoodId)).thenReturn(false);

        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> service.create(request(location(null, null, null, neighborhoodId)), identity(), null));
        assertEquals("Barrio no encontrado", exception.getMessage());
        verify(tickets, never()).save(any());
    }

    @Test
    void validRequiredLocationAllowsCreation() {
        requestType.setRequiresLocation(true);
        allowLowRisk();

        assertNotNull(service.create(request(location("Av. Siempre Viva 742", null, null, null)),
                identity(), null).ticketId());
        verify(locations).save(any(TicketLocation.class));
    }

    @Test
    void optionalNullLocationAllowsCreationAndHibernateOwnsTicketTimestamps() {
        allowLowRisk();

        assertNotNull(service.create(request(), identity(), null).ticketId());
        verify(tickets).save(argThat(ticket -> ticket.getCreatedAt() == null
                && ticket.getUpdatedAt() == null && ticket.getStatusChangedAt() != null));
        verify(locations, never()).save(any());
    }

    private CreateTicketRequest request() {
        return new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of("answer", true), null);
    }

    private CreateTicketRequest request(CreateTicketRequest.LocationData location) {
        return new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of("answer", true), location);
    }

    private CreateTicketRequest.LocationData location(String addressLine, BigDecimal latitude,
                                                      BigDecimal longitude, UUID neighborhoodId) {
        return new CreateTicketRequest.LocationData(addressLine, null, null, neighborhoodId,
                latitude, longitude, null);
    }

    private void allowLowRisk() {
        when(risks.calculateRisk(any(), any(), any())).thenReturn(Risk.LOW);
    }

    private AuthenticatedIdentity identity() {
        return new AuthenticatedIdentity("citizen", UUID.randomUUID(), "Citizen", null, Set.of());
    }

    private RequestType requestType(boolean active) {
        Category category = new Category();
        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        RequestType type = new RequestType();
        type.setId(1L);
        type.setSubcategory(subcategory);
        type.setTicketType(TicketType.REQUEST);
        type.setResponsibleAreaId("AREA-1");
        type.setMinimumPriority(Priority.LOW);
        type.setBaseRisk(Risk.LOW);
        type.setAffectedPopulationFactor(BigDecimal.ZERO);
        type.setRequiresLocation(false);
        type.setActive(active);
        return type;
    }
}