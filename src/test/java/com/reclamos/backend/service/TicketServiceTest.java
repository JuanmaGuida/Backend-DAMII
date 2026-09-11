package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Suite fusionada tras el merge feature-nico -&gt; dev: cubre tanto Sprint 2
 * (startReview/correctClassification/routeToArea/listTickets, con AssertJ)
 * como create()/route() con SLA y adjuntos (con JUnit Assertions), sobre el
 * TicketService ya reconciliado. Un test de validación de ubicación
 * requerida ("falta la ubicación cuando el RequestType la exige") no se
 * pudo recuperar con su nombre/firma original de dev por cómo git fragmentó
 * el archivo en el merge — se reconstruyó como
 * locationRejectsMissingOrEmptyLocationWhenRequired a partir del código de
 * validateLocation; si dev tiene una versión distinta, reemplazar por esa.
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private RequestTypeRepository requestTypes;
    @Mock
    private TicketRepository tickets;
    @Mock
    private TicketActivityRepository activities;
    @Mock
    private TicketLocationRepository locations;
    @Mock
    private NeighborhoodRepository neighborhoods;
    @Mock
    private FormValidationService forms;
    @Mock
    private RiskCalculationService risks;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ModuleUserRepository moduleUsers;
    @Spy
    private TrackingCodeService trackingCodes = new TrackingCodeService();
    @Mock
    private TicketPublicIdGenerator publicIds;
    @Mock
    private SlaCalculationService sla;
    private final AttachmentService attachments = mock(AttachmentService.class);
    @Mock
    private Clock clock;

    @InjectMocks
    private TicketService service;

    private RequestType requestType;

    private final UUID ticketId = UUID.randomUUID();
    private final AuthenticatedIdentity actor = new AuthenticatedIdentity(
            "agent-1", UUID.randomUUID(), "Agente Uno", "area-obras", ModuleRole.AGENT);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "producerModuleId", "M2");
        ReflectionTestUtils.setField(service, "producerService", "help-center-api");

        lenient().when(clock.instant()).thenReturn(NOW);
        lenient().when(publicIds.generate(NOW)).thenReturn("TK-2026-000123");

        lenient().when(
                sla.calculateDueAt(any(), any(Priority.class), eq(SlaType.FIRST_RESPONSE))
        ).thenReturn(Optional.empty());

        lenient().when(
                sla.calculateResolutionDueAt(any(), any(Priority.class), any(TicketType.class))
        ).thenReturn(Optional.empty());

        requestType = requestType(true);

        lenient().when(requestTypes.findById(1L)).thenReturn(Optional.of(requestType));

        FormTemplate template = new FormTemplate();
        template.setId(3L);
        template.setRequestType(requestType);

        lenient().when(forms.resolveAndValidate(any(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> data = invocation.getArgument(1);
                    return new ResolvedForm(template, List.of(), data == null ? Map.of() : data);
                });

        lenient().when(tickets.save(any()))
                .thenAnswer(invocation -> {
                    Ticket ticket = invocation.getArgument(0);
                    if (ticket.getId() == null) {
                        ticket.setId(UUID.randomUUID());
                    }
                    return ticket;
                });

        lenient().when(attachments.validate(nullable(MultipartFile[].class)))
                .thenAnswer(invocation -> {
                    MultipartFile[] files = invocation.getArgument(0);
                    if (files == null || files.length == 0) {
                        return List.of();
                    }
                    return Arrays.stream(files)
                            .map(file -> new AttachmentService.ValidatedAttachment(
                                    file, file.getOriginalFilename(), file.getContentType(), file.getSize()))
                            .toList();
                });

        lenient().when(attachments.storeForTicket(any(), any(), anyList(), any())).thenReturn(List.of());
    }

    // ==================================================================
    // ---- create() ----
    // ==================================================================

    @Test
    void lowRiskWithoutEvidenceCreatesRegisteredServerClassifiedTicket() {
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));

        AuthenticatedIdentity citizen = identity();

        CreateTicketResponse response = service.create(request(), citizen, null);

        assertEquals(TicketStatus.REGISTERED, response.status());
        assertNotNull(response.trackingCode());
        assertEquals("TK-2026-000123", response.publicId());
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(response.publicId()));

        verify(tickets).save(argThat(ticket ->
                ticket.getCurrentStatus() == TicketStatus.REGISTERED
                        && ticket.getPublicId().equals(response.publicId())
                        && ticket.getCreatedAt().equals(NOW)
                        && ticket.getTicketType() == requestType.getTicketType()
                        && ticket.getResponsibleAreaId().equals("M6")
                        && ticket.getResponsibleAreaId().equals(requestType.getResponsibleAreaId())
                        && ticket.getFormTemplateId().equals(3L)
                        && ticket.getRequestType().getSubcategory().getCategory() != null
                        && !ticket.getTrackingCodeHash().equals(response.trackingCode())
        ));

        verify(activities).save(argThat(activity ->
                activity.getActionType() == ActivityType.TICKET_CREATED
                        && activity.getSequence() == 1
                        && activity.getActorType() == ActorType.CITIZEN
                        && citizen.citizenId().toString().equals(activity.getActorId())
                        && !citizen.subjectId().equals(activity.getActorId())
                        && activity.getSourceModuleId() == null
        ));
    }

    @Test
    void mediumRiskWithoutEvidenceCreatesTicket() {
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(36, Risk.MEDIUM));

        assertNotNull(service.create(request(), identity(), null).ticketId());
    }

    @Test
    void highAndCriticalRiskWithEvidenceCreateTickets() {
        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});

        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(62, Risk.HIGH), new RiskAssessment(100, Risk.CRITICAL));

        assertNotNull(service.create(request(), identity(), new MockMultipartFile[]{evidence}).ticketId());
        assertNotNull(service.create(request(), identity(), new MockMultipartFile[]{evidence}).ticketId());

        verify(attachments, times(2)).storeForTicket(any(), any(), argThat(items -> items.size() == 1), any());
    }

    @Test
    void highAndCriticalRiskWithoutEvidenceDoNotPersist() {
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
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

        assertThrows(ResourceNotFoundException.class, () -> service.create(
                new CreateTicketRequest(99L, "s", "d", Map.of(), null), identity(), null));

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
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));

        CreateTicketResponse first = service.create(request(), identity(), null);
        CreateTicketResponse second = service.create(request(), identity(), null);

        assertNotEquals(first.trackingCode(), second.trackingCode());

        verify(tickets, times(2)).save(argThat(ticket ->
                !ticket.getTrackingCodeHash().equals(first.trackingCode())
                        && !ticket.getTrackingCodeHash().equals(second.trackingCode())
        ));
    }

    @Test
    void createdTrackingCodeImmediatelyFindsTheSameTicket() {
        allowLowRisk();

        CreateTicketResponse created = service.create(request(), identity(), null);

        var savedTicket = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(savedTicket.capture());

        Ticket ticket = savedTicket.getValue();
        ticket.setCreatedAt(Instant.parse("2026-09-02T18:00:00Z"));

        when(tickets.findByTrackingCodeHash(trackingCodes.hash(created.trackingCode())))
                .thenReturn(Optional.of(ticket));

        var tracked = new TrackingService(tickets, trackingCodes).findByTrackingCode(created.trackingCode());

        assertEquals(created.publicId(), tracked.getPublicId());
        assertEquals(created.status(), tracked.getStatus());
    }

    @Test
    void minimumPriorityIsAlwaysAppliedAsFloor() {
        requestType.setMinimumPriority(Priority.HIGH);

        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.LOW));

        service.create(request(), identity(), null);

        verify(tickets).save(argThat(ticket -> ticket.getCurrentPriority() == Priority.HIGH));
    }

    // ==================================================================
    // ---- validación de ubicación en create() ----
    // ==================================================================

    @Test
    void locationRejectsMissingOrEmptyLocationWhenRequired() {
        requestType.setRequiresLocation(true);
        allowLowRisk();

        assertThrows(InvalidTicketRequestException.class,
                () -> service.create(request(), identity(), null));

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

        assertThrows(InvalidTicketRequestException.class, () -> service.create(
                request(location(null, new BigDecimal("90.1"), BigDecimal.ZERO, null)), identity(), null));

        assertThrows(InvalidTicketRequestException.class, () -> service.create(
                request(location(null, BigDecimal.ZERO, new BigDecimal("180.1"), null)), identity(), null));

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

        assertNotNull(service.create(
                request(location("Av. Siempre Viva 742", null, null, null)), identity(), null).ticketId());

        verify(locations).save(any(TicketLocation.class));
    }

    @Test
    void optionalNullLocationAllowsCreationAndUsesOneControlledCreationInstant() {
        allowLowRisk();

        assertNotNull(service.create(request(), identity(), null).ticketId());

        verify(tickets).save(argThat(ticket ->
                ticket.getCreatedAt().equals(clock.instant())
                        && ticket.getUpdatedAt() == null
                        && ticket.getStatusChangedAt() != null
        ));

        verify(locations, never()).save(any());
        verify(attachments, never()).storeForTicket(any(), any(), anyList(), any());
    }

    // ==================================================================
    // ---- SLA en create() ----
    // ==================================================================

    @Test
    void creationStoresCalculatedResolutionDueAt() {
        allowLowRisk();

        Instant dueAt = Instant.parse("2026-09-04T18:00:00Z");
        when(sla.calculateResolutionDueAt(clock.instant(), Priority.LOW, TicketType.REQUEST))
                .thenReturn(Optional.of(dueAt));

        service.create(request(), identity(), null);

        verify(tickets).save(argThat(ticket -> dueAt.equals(ticket.getResolutionDueAt())));
    }

    @Test
    void creationStoresFirstResponseDueAtFromCreatedAt() {
        allowLowRisk();

        Instant dueAt = Instant.parse("2026-09-04T14:00:00Z");
        when(sla.calculateDueAt(clock.instant(), Priority.LOW, SlaType.FIRST_RESPONSE))
                .thenReturn(Optional.of(dueAt));

        service.create(request(), identity(), null);

        verify(tickets).save(argThat(ticket -> dueAt.equals(ticket.getFirstResponseDueAt())));
    }

    @Test
    void creationWithoutPolicyStoresNullResolutionDueAt() {
        allowLowRisk();

        service.create(request(), identity(), null);

        verify(tickets).save(argThat(ticket -> ticket.getResolutionDueAt() == null));
    }

    @Test
    void criticalCreationUsesTheCriticalPolicyResult() {
        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});

        Instant dueAt = Instant.parse("2026-09-04T16:00:00Z");

        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.CRITICAL));

        when(sla.calculateResolutionDueAt(clock.instant(), Priority.CRITICAL, TicketType.REQUEST))
                .thenReturn(Optional.of(dueAt));

        service.create(request(), identity(), new MockMultipartFile[]{evidence});

        verify(tickets).save(argThat(ticket ->
                ticket.getCurrentPriority() == Priority.CRITICAL && dueAt.equals(ticket.getResolutionDueAt())
        ));
    }

    // ==================================================================
    // ---- route() ----
    // ==================================================================

    @Test
    void routingRecalculatesWhenPolicyRequiresIt() {
        UUID id = UUID.randomUUID();

        Ticket ticket = new Ticket();
        ticket.setCurrentPriority(Priority.CRITICAL);
        ticket.setTicketType(TicketType.REQUEST);
        ticket.setResolutionDueAt(Instant.parse("2026-09-04T13:00:00Z"));

        Instant routedAt = Instant.parse("2026-09-05T12:00:00Z");
        Instant dueAt = Instant.parse("2026-09-05T14:00:00Z");

        ticket.setCreatedAt(clock.instant());

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(sla.calculateResolutionDueAt(clock.instant(), Priority.CRITICAL, TicketType.REQUEST))
                .thenReturn(Optional.of(dueAt));
        when(tickets.save(ticket)).thenReturn(ticket);

        Ticket routed = service.route(id, "AREA-2", routedAt);

        assertEquals(dueAt, routed.getResolutionDueAt());
        assertEquals(TicketStatus.ROUTED, routed.getCurrentStatus());
    }

    @Test
    void routingRecalculatesFromCreatedAtAndPreservesTheSameResult() {
        UUID id = UUID.randomUUID();
        Instant originalDueAt = Instant.parse("2026-09-06T12:00:00Z");
        Ticket ticket = routedTicket(Priority.HIGH, originalDueAt);
        ticket.setCreatedAt(clock.instant());

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(sla.calculateResolutionDueAt(clock.instant(), Priority.HIGH, TicketType.REQUEST))
                .thenReturn(Optional.of(originalDueAt));
        when(tickets.save(ticket)).thenReturn(ticket);

        assertEquals(originalDueAt, service.route(id, "AREA-2", clock.instant()).getResolutionDueAt());

        verify(sla).calculateResolutionDueAt(clock.instant(), Priority.HIGH, TicketType.REQUEST);
    }

    @Test
    void routingSetsMissingDueAtFromCreatedAt() {
        UUID id = UUID.randomUUID();
        Ticket ticket = routedTicket(Priority.MEDIUM, null);
        Instant dueAt = Instant.parse("2026-09-07T12:00:00Z");
        ticket.setCreatedAt(clock.instant());

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(sla.calculateResolutionDueAt(clock.instant(), Priority.MEDIUM, TicketType.REQUEST))
                .thenReturn(Optional.of(dueAt));
        when(tickets.save(ticket)).thenReturn(ticket);

        assertEquals(dueAt, service.route(id, "AREA-2", clock.instant()).getResolutionDueAt());
    }

    @Test
    void routingAfterPriorityChangeStillUsesOriginalCreatedAt() {
        UUID id = UUID.randomUUID();
        Ticket ticket = routedTicket(Priority.HIGH, Instant.parse("2026-09-10T12:00:00Z"));
        ticket.setCreatedAt(Instant.parse("2026-09-01T12:00:00Z"));

        Instant recalculated = Instant.parse("2026-09-04T12:00:00Z");

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(sla.calculateResolutionDueAt(ticket.getCreatedAt(), Priority.HIGH, TicketType.REQUEST))
                .thenReturn(Optional.of(recalculated));
        when(tickets.save(ticket)).thenReturn(ticket);

        assertEquals(recalculated, service.route(id, "AREA-2", clock.instant()).getResolutionDueAt());

        verify(sla).calculateResolutionDueAt(ticket.getCreatedAt(), Priority.HIGH, TicketType.REQUEST);
    }

    @Test
    void routingPreservesFirstResponseDueAt() {
        UUID id = UUID.randomUUID();
        Instant firstResponseDueAt = Instant.parse("2026-09-02T12:00:00Z");
        Ticket ticket = routedTicket(Priority.MEDIUM, Instant.parse("2026-09-05T12:00:00Z"));
        ticket.setCreatedAt(Instant.parse("2026-09-01T12:00:00Z"));
        ticket.setFirstResponseDueAt(firstResponseDueAt);

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(sla.calculateResolutionDueAt(ticket.getCreatedAt(), Priority.MEDIUM, TicketType.REQUEST))
                .thenReturn(Optional.of(ticket.getResolutionDueAt()));
        when(tickets.save(ticket)).thenReturn(ticket);

        Ticket routed = service.route(id, "AREA-2", clock.instant());

        assertEquals(firstResponseDueAt, routed.getFirstResponseDueAt());
        verify(sla, never()).calculateDueAt(any(), any(Priority.class), eq(SlaType.FIRST_RESPONSE));
    }

    @Test
    void duplicateCannotBeRoutedOrReceiveAnIndependentSla() {
        UUID id = UUID.randomUUID();
        Ticket duplicate = routedTicket(Priority.HIGH, null);
        duplicate.setCurrentStatus(TicketStatus.DUPLICATE);
        duplicate.setCreatedAt(Instant.parse("2026-09-01T12:00:00Z"));

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(duplicate));

        assertThrows(InvalidTicketRequestException.class, () -> service.route(id, "AREA-2", clock.instant()));

        verifyNoInteractions(sla);
        verify(tickets, never()).save(any());
    }

    private Ticket routedTicket(Priority priority, Instant dueAt) {
        Ticket ticket = new Ticket();
        ticket.setCurrentPriority(priority);
        ticket.setTicketType(TicketType.REQUEST);
        ticket.setResolutionDueAt(dueAt);
        return ticket;
    }

    // ==================================================================
    // ---- adjuntos en create() ----
    // ==================================================================

    @Test
    void voluntaryEvidenceIsStoredForLowRisk() {
        allowLowRisk();

        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.webp", "image/webp", new byte[]{1});

        service.create(request(), identity(), new MockMultipartFile[]{evidence});

        verify(attachments).storeForTicket(
                any(Ticket.class), any(AuthenticatedIdentity.class),
                argThat(items -> items.size() == 1 && items.getFirst().file() == evidence), any());
    }

    @Test
    void storageFailurePreventsActivityAndSuccessfulResponse() {
        allowLowRisk();

        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});

        doThrow(new AttachmentStorageUnavailableException())
                .when(attachments).storeForTicket(any(), any(), anyList(), any());

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

        service.create(new CreateTicketRequest(1L, "Resumen", "Descripción", formData, null), identity(), null);

        verify(tickets).save(argThat(ticket ->
                ticket.getFormTemplateId().equals(3L)
                        && Boolean.FALSE.equals(ticket.getFormData().get("danger"))
                        && Integer.valueOf(0).equals(ticket.getFormData().get("amount"))
        ));
    }

    @Test
    void requestTypeWithoutTemplatePersistsNullTemplateForEmptyForm() {
        when(forms.resolveAndValidate(any(), any()))
                .thenReturn(new ResolvedForm(null, List.of(), Map.of()));

        allowLowRisk();

        service.create(new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of(), null), identity(), null);

        verify(tickets).save(argThat(ticket ->
                ticket.getFormTemplateId() == null && ticket.getFormData().isEmpty()
        ));
    }

    // ==================================================================
    // ---- fixtures de create()/route() ----
    // ==================================================================

    private CreateTicketRequest request() {
        return new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of("answer", true), null);
    }

    private CreateTicketRequest request(CreateTicketRequest.LocationData location) {
        return new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of("answer", true), location);
    }

    private CreateTicketRequest.LocationData location(String addressLine, BigDecimal latitude,
                                                       BigDecimal longitude, UUID neighborhoodId) {
        return new CreateTicketRequest.LocationData(
                addressLine, null, null, neighborhoodId, latitude, longitude, null);
    }

    private void allowLowRisk() {
        when(risks.calculateRisk(any(), any(ResolvedForm.class))).thenReturn(new RiskAssessment(0, Risk.LOW));
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

    // ==================================================================
    // ---- startReview (Sprint 2) ----
    // ==================================================================

    @Test
    void startReviewMovesRegisteredTicketToInReviewAndAssignsAgent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setAssignedAgent(null);
        ModuleUser agentUser = new ModuleUser();
        agentUser.setId(42L);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(moduleUsers.findByCitizenId(actor.citizenId())).thenReturn(Optional.of(agentUser));

        TicketResponse response = service.startReview(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(ticket.getAssignedAgent()).isEqualTo(agentUser);
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        TicketActivity activity = activityCaptor.getValue();
        assertThat(activity.getActionType()).isEqualTo(ActivityType.REVIEW_STARTED);
        assertThat(activity.getPreviousStatus()).isEqualTo(TicketStatus.REGISTERED);
        assertThat(activity.getNewStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(activity.getSequence()).isEqualTo(1);
    }

    @Test
    void startReviewDoesNotOverwriteAnAlreadyAssignedAgent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ModuleUser originalAgent = new ModuleUser();
        originalAgent.setId(7L);
        ticket.setAssignedAgent(originalAgent);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.startReview(ticketId, actor);

        assertThat(ticket.getAssignedAgent()).isEqualTo(originalAgent);
        verify(moduleUsers, never()).findByCitizenId(any());
    }

    @Test
    void startReviewOnNonRegisteredTicketThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.startReview(ticketId, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verify(activities, never()).save(any());
    }

    @Test
    void startReviewOnMissingTicketThrowsNotFound() {
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startReview(ticketId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================================================================
    // ---- correctClassification (Sprint 2) ----
    // ==================================================================

    @Test
    void correctClassificationRecalculatesAreaAffectedCountFormTemplateAndPriorityFromNewRequestType() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        String originalPublicId = ticket.getPublicId();
        // formData del RequestType viejo no debe sobrevivir a la reclasificación
        // — ver assertion de formData al final del test.
        ticket.setFormData(new HashMap<>(Map.of("waterHeight", 35)));
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Palermo");
        neighborhood.setPopulation(200_000);
        TicketLocation location = new TicketLocation();
        location.setNeighborhood(neighborhood);

        // minimumPriority HIGH con baseRisk LOW: la prioridad final tiene que
        // quedar en HIGH por el piso de minimumPriority, no por la
        // currentPriority anterior del ticket (que acá es LOW, más baja).
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.HIGH, Risk.LOW, new BigDecimal("0.1000"));
        FormTemplate newFormTemplate = new FormTemplate();
        newFormTemplate.setId(99L);

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.of(location));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(forms.resolveActiveTemplate(newRequestType)).thenReturn(newFormTemplate);

        TicketResponse response = service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getResponsibleAreaId()).isEqualTo("obras-hidraulicas");
        assertThat(ticket.getPublicId()).isEqualTo(originalPublicId);
        assertThat(ticket.getEstimatedAffectedCount()).isEqualTo(20_000);
        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.HIGH);
        assertThat(ticket.getFormTemplateId()).isEqualTo(99L);
        assertThat(response.getRequestTypeCode()).isEqualTo("FLOODING");
        assertThat(ticket.getFormData()).isEmpty();
    }

    /**
     * Guía funcional §3 ("REGLA DE EVOLUCIÓN"): el piso de "nunca baja" sólo
     * aplica a la recalculación periódica automática (por edad/SLA); una
     * corrección de RequestType durante la primera IN_REVIEW todavía forma
     * parte de la clasificación inicial y puede bajar la prioridad.
     */
    @Test
    void correctClassificationCanLowerPriorityWhenNewRequestTypeHasLowerMinimumAndBaseRisk() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.CRITICAL);
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.LOW, Risk.LOW, new BigDecimal("0.1000"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.LOW);
    }

    @Test
    void correctClassificationWithoutLocationEstimatesZeroAffectedCount() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.MEDIUM, new BigDecimal("0.1000"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getEstimatedAffectedCount()).isZero();
    }

    @Test
    void correctClassificationRejectsWhenTicketIsNotInFirstReview() {
        Ticket ticket = ticket(TicketStatus.ROUTED, Priority.LOW);

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.correctClassification(ticketId, 20L, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verify(requestTypes, never()).findById(any());
    }

    /**
     * Cubre el guard en sí (classificationFinalizedAt != null -&gt; conflicto).
     * Ese campo se fija en TicketService.routeToArea — ver
     * routeToAreaKeepsOriginalClassificationFinalizedAtOnSecondRouting.
     */
    @Test
    void correctClassificationRejectsWhenClassificationAlreadyFinalized() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setClassificationFinalizedAt(Instant.now());

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.correctClassification(ticketId, 20L, actor))
                .isInstanceOf(TicketStateConflictException.class);
    }

    @Test
    void correctClassificationRejectsInactiveOrMissingRequestType() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.correctClassification(ticketId, 99L, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================================================================
    // ---- routeToArea (Sprint 2) ----
    // ==================================================================

    @Test
    void routeToAreaMovesInReviewTicketToRoutedAndWritesOutboxEvent() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.HIGH);
        ticket.setResponsibleAreaId("M6");
        String originalPublicId = ticket.getPublicId();

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketResponse response = service.routeToArea(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(ticket.getPublicId()).isEqualTo(originalPublicId);
        assertThat(ticket.getClassificationFinalizedAt()).isNotNull();

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActionType()).isEqualTo(ActivityType.ROUTED);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("ticketUpdated");
        assertThat(event.getTicket()).isSameAs(ticket);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
        assertThat(data.get("ticketId")).isEqualTo(ticketId);
        assertThat(data.get("publicId")).isEqualTo(originalPublicId);
        assertThat(data.get("updateType")).isEqualTo("ROUTED");
        assertThat(data.get("responsibleAreaId")).isEqualTo("M6");
        assertThat(event.getPayload().get("subject")).isEqualTo("tickets/" + ticketId);
    }

    @Test
    void routeToAreaSkipsOutboxWhenAreaIsSelfManaged() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId("M2");

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.routeToArea(ticketId, actor);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void routeToAreaOnWrongStateThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.routeToArea(ticketId, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void routeToAreaWithoutResponsibleAreaThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId(null);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.routeToArea(ticketId, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verify(tickets, never()).save(any());
    }

    /**
     * ROUTED -&gt; RETURNED -&gt; IN_REVIEW -&gt; nueva derivación no debe volver
     * a mover classificationFinalizedAt (ver también
     * correctClassificationRejectsWhenClassificationAlreadyFinalized).
     */
    @Test
    void routeToAreaKeepsOriginalClassificationFinalizedAtOnSecondRouting() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId("M6");
        Instant firstFinalization = Instant.now().minusSeconds(3600);
        ticket.setClassificationFinalizedAt(firstFinalization);

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(2);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.routeToArea(ticketId, actor);

        assertThat(ticket.getClassificationFinalizedAt()).isEqualTo(firstFinalization);
    }

    // ==================================================================
    // ---- listTickets (Sprint 2) ----
    // ==================================================================

    @Test
    void listTicketsMapsNeighborhoodFromBatchedLocations() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Recoleta");
        neighborhood.setPopulation(150_000);
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setNeighborhood(neighborhood);

        TicketFilter filter = new TicketFilter(null, null, null, null, null);
        Pageable pageable = Pageable.unpaged();
        Page<Ticket> page = new PageImpl<>(List.of(ticket));

        when(tickets.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(locations.findAllByTicket_IdIn(anyList())).thenReturn(List.of(location));

        Page<TicketResponse> result = service.listTickets(filter, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getNeighborhoodName()).isEqualTo("Recoleta");
    }

    @Test
    void listTicketsLeavesNeighborhoodNullWhenTicketHasNoLocation() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);

        TicketFilter filter = new TicketFilter(null, null, null, null, null);
        Pageable pageable = Pageable.unpaged();
        Page<Ticket> page = new PageImpl<>(List.of(ticket));

        when(tickets.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(locations.findAllByTicket_IdIn(anyList())).thenReturn(List.of());

        Page<TicketResponse> result = service.listTickets(filter, pageable);

        assertThat(result.getContent().get(0).getNeighborhoodId()).isNull();
    }

    /**
     * ?sort=notAField,desc se valida contra la whitelist propia
     * (TicketService.SORTABLE_TICKET_PROPERTIES) antes de tocar el
     * repository, sin depender de ninguna clase interna de Spring Data.
     */
    @Test
    void listTicketsWithInvalidSortFieldThrowsBadRequestWithoutQueryingRepository() {
        TicketFilter filter = new TicketFilter(null, null, null, null, null);
        Pageable pageable = PageRequest.of(0, 20, Sort.by("notAField"));

        assertThatThrownBy(() -> service.listTickets(filter, pageable))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(tickets, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    // ==================================================================
    // ---- fixtures Sprint 2 ----
    // ==================================================================

    private Ticket ticket(TicketStatus status, Priority priority) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Infraestructura");

        Subcategory subcategory = new Subcategory();
        subcategory.setId(10L);
        subcategory.setCategory(category);
        subcategory.setName("Vía pública");

        RequestType type = requestTypeSprint2(5L, "POTHOLE", "obras-viales", Priority.LOW,
                new BigDecimal("0.0500"));
        type.setSubcategory(subcategory);

        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setPublicId("TK-2026-000001");
        ticket.setRequestType(type);
        ticket.setTicketType(TicketType.COMPLAINT);
        ticket.setResponsibleAreaId("obras-viales");
        ticket.setSummary("Bache en la vereda");
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(priority);
        ticket.setEstimatedAffectedCount(0);
        ticket.setEscalated(false);
        ticket.setStatusChangedAt(Instant.now());
        return ticket;
    }

    private RequestType requestTypeSprint2(Long id, String code, String responsibleAreaId,
                                            Priority minimumPriority, BigDecimal affectedPopulationFactor) {
        return requestTypeSprint2(id, code, responsibleAreaId, minimumPriority, Risk.LOW, affectedPopulationFactor);
    }

    /**
     * baseRisk lo usa correctClassification para recalcular currentPriority
     * (ver correctClassificationCanLowerPriorityWhen...). El overload de 5
     * argumentos delega acá con Risk.LOW por default para los tests a los
     * que no les importa ese valor. Nombrado *Sprint2 (en vez de sobrecargar
     * requestType(...)) para no colisionar con el fixture requestType(boolean)
     * de la sección create()/route().
     */
    private RequestType requestTypeSprint2(Long id, String code, String responsibleAreaId,
                                            Priority minimumPriority, Risk baseRisk,
                                            BigDecimal affectedPopulationFactor) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Infraestructura");

        Subcategory subcategory = new Subcategory();
        subcategory.setId(10L);
        subcategory.setCategory(category);
        subcategory.setName("Vía pública");

        RequestType type = new RequestType();
        type.setId(id);
        type.setCode(code);
        type.setName(code);
        type.setSubcategory(subcategory);
        type.setTicketType(TicketType.COMPLAINT);
        type.setResponsibleAreaId(responsibleAreaId);
        type.setMinimumPriority(minimumPriority);
        type.setBaseRisk(baseRisk);
        type.setAffectedPopulationFactor(affectedPopulationFactor);
        type.setActive(true);
        return type;
    }
}
