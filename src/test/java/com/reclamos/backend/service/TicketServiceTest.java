package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.AttachmentStorageUnavailableException;
import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final TrackingCodeService trackingCodes = new TrackingCodeService();
    private TicketService service;
    private RequestType requestType;

    @BeforeEach
    void setUp() {
        reset(requestTypes, tickets, activities, locations, neighborhoods, forms, risks, attachments);
        service = new TicketService(requestTypes, tickets, activities, locations, neighborhoods, forms, risks,
                trackingCodes, attachments);
        requestType = requestType(true);
        when(requestTypes.findById(1L)).thenReturn(Optional.of(requestType));
        FormTemplate template = new FormTemplate();
        template.setId(3L);
        template.setRequestType(requestType);
        when(forms.resolveAndValidate(any(), any())).thenAnswer(invocation -> {
            Map<String, Object> data = invocation.getArgument(1);
            return new ResolvedForm(template, List.of(), data == null ? Map.of() : data);
        });
        when(tickets.save(any())).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(UUID.randomUUID());
            return ticket;
        });
        when(attachments.validate(nullable(org.springframework.web.multipart.MultipartFile[].class)))
                .thenAnswer(invocation -> {
                    org.springframework.web.multipart.MultipartFile[] files = invocation.getArgument(0);
                    if (files == null || files.length == 0) {
                        return List.of();
                    }
                    return java.util.Arrays.stream(files)
                            .map(file -> new AttachmentService.ValidatedAttachment(file, file.getOriginalFilename(),
                                    file.getContentType(), file.getSize()))
                            .toList();
                });
        when(attachments.storeForTicket(any(), any(), anyList(), any())).thenReturn(List.of());
    }

    @Test
    void lowRiskWithoutEvidenceCreatesRegisteredServerClassifiedTicket() {
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));
        CreateTicketResponse response = service.create(request(), identity(), null);

        assertEquals(TicketStatus.REGISTERED, response.status());
        assertNotNull(response.trackingCode());
        assertTrue(response.publicId().matches("OP-[0-9]{10}"));
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(response.publicId()));
        verify(tickets).save(argThat(ticket -> ticket.getCurrentStatus() == TicketStatus.REGISTERED
                && ticket.getTicketType() == requestType.getTicketType()
                && ticket.getResponsibleAreaId().equals("M6")
                && ticket.getResponsibleAreaId().equals(requestType.getResponsibleAreaId())
                && ticket.getFormTemplateId().equals(3L)
                && ticket.getRequestType().getSubcategory().getCategory() != null
                && !ticket.getTrackingCodeHash().equals(response.trackingCode())));
        verify(activities).save(argThat(activity -> activity.getActionType() == ActivityType.TICKET_CREATED
                && activity.getSequence() == 1));
    }

    @Test
    void mediumRiskWithoutEvidenceCreatesTicket() {
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(36, Risk.MEDIUM));
        assertNotNull(service.create(request(), identity(), null).ticketId());
    }

    @Test
    void highAndCriticalRiskWithEvidenceCreateTickets() {
        MockMultipartFile evidence = new MockMultipartFile("evidence", "photo.jpg", "image/jpeg", new byte[]{1});
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(62, Risk.HIGH), new RiskAssessment(100, Risk.CRITICAL));
        assertNotNull(service.create(request(), identity(), new MockMultipartFile[]{evidence}).ticketId());
        assertNotNull(service.create(request(), identity(), new MockMultipartFile[]{evidence}).ticketId());
        verify(attachments, times(2)).storeForTicket(any(), any(), argThat(items -> items.size() == 1), any());
    }

    @Test
    void highAndCriticalRiskWithoutEvidenceDoNotPersist() {
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(62, Risk.HIGH), new RiskAssessment(100, Risk.CRITICAL));
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
        when(forms.resolveAndValidate(any(), any())).thenThrow(new FormValidationException("invalid"));
        assertThrows(FormValidationException.class, () -> service.create(request(), identity(), null));
        verify(tickets, never()).save(any());
    }

    @Test
    void trackingCodesAreDifferentAndOnlyTheirHashesAreStored() {
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));
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
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));
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
        verify(attachments, never()).storeForTicket(any(), any(), anyList(), any());
    }

    @Test
    void voluntaryEvidenceIsStoredForLowRisk() {
        allowLowRisk();
        MockMultipartFile evidence = new MockMultipartFile("evidence", "photo.webp", "image/webp",
                new byte[]{1});

        service.create(request(), identity(), new MockMultipartFile[]{evidence});

        verify(attachments).storeForTicket(any(Ticket.class), any(AuthenticatedIdentity.class),
                argThat(items -> items.size() == 1 && items.getFirst().file() == evidence), any());
    }

    @Test
    void storageFailurePreventsActivityAndSuccessfulResponse() {
        allowLowRisk();
        MockMultipartFile evidence = new MockMultipartFile("evidence", "photo.jpg", "image/jpeg",
                new byte[]{1});
        doThrow(new AttachmentStorageUnavailableException()).when(attachments)
                .storeForTicket(any(), any(), anyList(), any());

        assertThrows(AttachmentStorageUnavailableException.class,
                () -> service.create(request(), identity(), new MockMultipartFile[]{evidence}));

        verify(activities, never()).save(any());
    }

    @Test
    void persistsFalseZeroAndExactTemplateId() {
        allowLowRisk();
        Map<String, Object> formData = new HashMap<>();
        formData.put("danger", false);
        formData.put("amount", 0);

        service.create(new CreateTicketRequest(1L, "Resumen", "Descripción", formData, null),
                identity(), null);

        verify(tickets).save(argThat(ticket -> ticket.getFormTemplateId().equals(3L)
                && Boolean.FALSE.equals(ticket.getFormData().get("danger"))
                && Integer.valueOf(0).equals(ticket.getFormData().get("amount"))));
    }

    @Test
    void requestTypeWithoutTemplatePersistsNullTemplateForEmptyForm() {
        when(forms.resolveAndValidate(any(), any())).thenReturn(new ResolvedForm(null, List.of(), Map.of()));
        allowLowRisk();

        service.create(new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of(), null),
                identity(), null);

        verify(tickets).save(argThat(ticket -> ticket.getFormTemplateId() == null
                && ticket.getFormData().isEmpty()));
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
        when(risks.calculateRisk(any(), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));
    }

    private AuthenticatedIdentity identity() {
        return new AuthenticatedIdentity("citizen", UUID.randomUUID(), "Citizen", null, ModuleRole.CITIZEN);
    }

    private RequestType requestType(boolean active) {
        Category category = new Category();
        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        RequestType type = new RequestType();
        type.setId(1L);
        type.setSubcategory(subcategory);
        type.setTicketType(TicketType.REQUEST);
        type.setResponsibleAreaId("M6");
        type.setMinimumPriority(Priority.LOW);
        type.setBaseRisk(Risk.LOW);
        type.setAffectedPopulationFactor(BigDecimal.ZERO);
        type.setRequiresLocation(false);
        type.setActive(active);
        return type;
    }
}
