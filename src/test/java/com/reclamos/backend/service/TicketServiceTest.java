package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CancelTicketRequest;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.dto.response.StaffTicketDetailResponse;
import com.reclamos.backend.dto.response.TicketDetailResponse;
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
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * como create() con SLA y adjuntos (con JUnit Assertions), sobre el
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
    private AttachmentRepository attachmentRepository;
    @Mock
    private TicketLocationRepository locations;
    @Mock
    private NeighborhoodRepository neighborhoods;
    @Mock
    private FormValidationService forms;
    @Mock
    private RiskCalculationService risks;
    @Mock
    private TicketOutboxService ticketOutboxService;
    @Mock
    private TicketCancellationRepository cancellationRepository;
    @Mock
    private ModuleUserRepository moduleUsers;
    @Spy
    private TrackingCodeService trackingCodes = new TrackingCodeService();
    @Mock
    private TicketPublicIdGenerator publicIds;
    @Mock
    private TicketSlaService ticketSlaService;
    @Mock
    private InformationRequestService informationRequestService;
    private final AttachmentService attachments = mock(AttachmentService.class);
    @Mock
    private AnonymousTicketCredentialService anonymousTicketCredentialService;
    @Mock
    private AnonymousContactValidator anonymousContactValidator;
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
        lenient().when(clock.instant()).thenReturn(NOW);
        lenient().when(publicIds.generate(NOW)).thenReturn("TK-2026-000123");
        requestType = requestType(true);

        lenient().when(requestTypes.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> requestTypes.findById(invocation.getArgument(0)));
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
        verify(ticketOutboxService).ticketCreated(argThat(ticket ->
                ticket.getCurrentStatus() == TicketStatus.REGISTERED
                        && citizen.citizenId().equals(ticket.getCitizenId())),
                nullable(TicketLocation.class), eq(List.of()), nullable(Instant.class));
    }

    @Test
    void identifiedSelfManagedCreationAlsoPublishesTicketCreated() {
        requestType.setResponsibleAreaId("M2");
        allowLowRisk();

        service.create(request(), identity(), null);

        verify(ticketOutboxService).ticketCreated(argThat(ticket ->
                "M2".equals(ticket.getResponsibleAreaId())), nullable(TicketLocation.class), eq(List.of()),
                nullable(Instant.class));
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
    void criticalPriorityCreatesRegisteredEscalatedTicketAndOrderedActivities() {
        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(100, Risk.CRITICAL));
        when(activities.countByTicketId(any())).thenReturn(1);

        CreateTicketResponse response = service.create(
                request(), identity(), new MockMultipartFile[]{evidence});

        assertThat(response.status()).isEqualTo(TicketStatus.REGISTERED);
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets, times(2)).save(ticketCaptor.capture());
        Ticket ticket = ticketCaptor.getValue();
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.REGISTERED);
        assertThat(ticket.isEscalated()).isTrue();
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.CRITICAL_PRIORITY);
        assertThat(ticket.getEscalatedAt()).isEqualTo(NOW);

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities, times(2)).save(activityCaptor.capture());
        assertThat(activityCaptor.getAllValues()).extracting(TicketActivity::getActionType)
                .containsExactly(ActivityType.TICKET_CREATED, ActivityType.ESCALATED);
        TicketActivity escalation = activityCaptor.getAllValues().get(1);
        assertThat(escalation.getSequence()).isEqualTo(2);
        assertThat(escalation.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(escalation.getActorId()).isNull();
        assertThat(escalation.getReasonCode()).isEqualTo("CRITICAL_PRIORITY");
        assertThat(escalation.getPreviousStatus()).isEqualTo(TicketStatus.REGISTERED);
        assertThat(escalation.getNewStatus()).isEqualTo(TicketStatus.REGISTERED);
    }

    @Test
    void highPriorityDoesNotActivateAutomaticEscalation() {
        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(62, Risk.HIGH));

        service.create(request(), identity(), new MockMultipartFile[]{evidence});

        verify(tickets).save(argThat(ticket -> !ticket.isEscalated()
                && ticket.getEscalationReasonCode() == null
                && ticket.getEscalatedAt() == null));
        verify(activities).save(argThat(activity -> activity.getActionType() == ActivityType.TICKET_CREATED));
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
    void anonymousEvidenceRequiredDoesNotGenerateCredentialsOrLeaveEffects() {
        requestType.setAllowsAnonymous(true);
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(62, Risk.HIGH));

        assertThrows(EvidenceRequiredException.class, () -> service.create(request(), null, null));

        verifyNoInteractions(anonymousTicketCredentialService, anonymousContactValidator);
        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
        verifyNoInteractions(ticketSlaService, ticketOutboxService);
        verify(attachments, never()).storeForTicket(any(), any(), anyList(), any());
    }

    @Test
    void anonymousInitialEvidenceUsesCitizenActorWithoutAnId() {
        requestType.setAllowsAnonymous(true);
        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(62, Risk.HIGH));
        when(anonymousTicketCredentialService.prepare(null)).thenReturn(
                new AnonymousTicketCredentialService.CredentialMaterial("bcrypt-hash", "generated-secret"));
        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});

        service.create(request(), null, new MultipartFile[]{evidence});

        verify(attachments).storeForTicket(argThat(Ticket::isAnonymous), isNull(), anyList(), eq(NOW));
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
        when(ticketSlaService.findDeadlineSnapshot(ticket))
                .thenReturn(new TicketSlaService.DeadlineSnapshot(null, null));

        var tracked = new TrackingService(tickets, trackingCodes, ticketSlaService)
                .findByTrackingCode(created.trackingCode());

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
        TicketSla resolutionSla = new TicketSla();
        Instant dueAt = NOW.plus(Duration.ofHours(12));
        resolutionSla.setDueAt(dueAt);
        when(ticketSlaService.startInitialResolutionCycle(any(Ticket.class), eq(NOW)))
                .thenReturn(Optional.of(resolutionSla));

        service.create(request(), identity(), null);

        verify(ticketSlaService).startInitialResolutionCycle(any(Ticket.class), eq(NOW));
        verify(ticketOutboxService).ticketCreated(any(), any(), any(), eq(dueAt));
    }

    @Test
    void creationStoresNearDueDeadlineWithCleanMilestoneMarkers() {
        allowLowRisk();

        service.create(request(), identity(), null);

        verify(ticketSlaService).startInitialResolutionCycle(any(Ticket.class), eq(NOW));
    }

    @Test
    void creationStartsFirstResponseCycleFromCreatedAt() {
        allowLowRisk();

        service.create(request(), identity(), null);

        verify(ticketSlaService).startFirstResponseCycle(any(Ticket.class), eq(NOW));
    }

    @Test
    void creationWithoutResolutionPolicyPublishesNullDerivedDeadline() {
        allowLowRisk();

        service.create(request(), identity(), null);

        verify(ticketOutboxService).ticketCreated(any(), any(), any(), isNull());
    }

    @Test
    void criticalCreationStartsResolutionCycleAndKeepsCriticalEscalation() {
        MockMultipartFile evidence = new MockMultipartFile(
                "evidence", "photo.jpg", "image/jpeg", new byte[]{1});

        when(risks.calculateRisk(any(RequestType.class), any(ResolvedForm.class)))
                .thenReturn(new RiskAssessment(0, Risk.CRITICAL));

        service.create(request(), identity(), new MockMultipartFile[]{evidence});

        verify(ticketSlaService).startInitialResolutionCycle(
                argThat(ticket -> ticket.getCurrentPriority() == Priority.CRITICAL), eq(NOW));
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
        assertThat(activity.getOccurredAt()).isEqualTo(NOW);
        assertThat(activity.getActorType()).isEqualTo(ActorType.AGENT);
        assertThat(activity.getActorId()).isEqualTo(actor.citizenId().toString());
        assertThat(activity.getActorId()).isNotEqualTo(actor.subjectId());
        assertThat(ticket.getStatusChangedAt()).isEqualTo(NOW);
        verify(ticketSlaService).completeFirstResponseCycle(ticket, NOW);
        verify(ticketOutboxService).statusChanged(ticket, null, NOW);
    }

    @Test
    void anonymousCreationUsesTheExistingPipelineWithoutCitizenOrModuleUser() {
        allowLowRisk();
        requestType.setAllowsAnonymous(true);
        String passwordHash = "$2a$10$anonymous-hash";
        when(anonymousTicketCredentialService.prepare(null)).thenReturn(
                new AnonymousTicketCredentialService.CredentialMaterial(passwordHash, "generated-secret"));

        CreateTicketResponse response = service.create(request(), null, null);

        assertEquals("generated-secret", response.generatedAnonymousAccessPassword());
        verify(tickets).save(argThat(ticket ->
                ticket.isAnonymous()
                        && ticket.getCitizenId() == null
                        && passwordHash.equals(ticket.getAnonymousAccessPasswordHash())
                        && ticket.getCurrentStatus() == TicketStatus.REGISTERED));
        verify(activities).save(argThat(activity ->
                activity.getActorType() == ActorType.CITIZEN && activity.getActorId() == null));
        verify(attachments, never()).storeForTicket(any(), any(), anyList(), any());
        verifyNoInteractions(moduleUsers);
        verify(ticketOutboxService).ticketCreated(argThat(Ticket::isAnonymous),
                nullable(TicketLocation.class), eq(List.of()), nullable(Instant.class));
    }

    @Test
    void anonymousCreationPersistsValidatedContactAndDoesNotReturnProvidedPassword() {
        allowLowRisk();
        requestType.setAllowsAnonymous(true);
        CreateTicketRequest request = new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of(), null,
                "chosen-password", new CreateTicketRequest.AnonymousContact(
                AnonymousContactChannel.EMAIL, " person@example.com "));
        when(anonymousContactValidator.validate(request.anonymousContact())).thenReturn(
                new AnonymousContactValidator.ValidatedContact(
                        AnonymousContactChannel.EMAIL, "person@example.com"));
        when(anonymousTicketCredentialService.prepare("chosen-password")).thenReturn(
                new AnonymousTicketCredentialService.CredentialMaterial("bcrypt-hash", null));

        CreateTicketResponse response = service.create(request, null, null);

        assertNull(response.generatedAnonymousAccessPassword());
        verify(tickets).save(argThat(ticket ->
                ticket.getAnonymousContactChannel() == AnonymousContactChannel.EMAIL
                        && "person@example.com".equals(ticket.getAnonymousContactValue())
                        && "bcrypt-hash".equals(ticket.getAnonymousAccessPasswordHash())));
    }

    @Test
    void anonymousCreationRejectsDisallowedRequestTypeBeforeCredentialsOrEffects() {
        requestType.setAllowsAnonymous(false);

        assertThrows(InvalidTicketRequestException.class, () -> service.create(request(), null, null));

        verifyNoInteractions(anonymousTicketCredentialService, anonymousContactValidator);
        verify(tickets, never()).save(any());
        verifyNoInteractions(activities, ticketSlaService, ticketOutboxService);
    }

    @Test
    void anonymousCreationRejectsInactiveRequestTypeBeforeCredentialsOrEffects() {
        requestType.setAllowsAnonymous(true);
        requestType.setActive(false);

        assertThrows(InvalidTicketRequestException.class, () -> service.create(request(), null, null));

        verifyNoInteractions(anonymousTicketCredentialService, anonymousContactValidator);
        verify(tickets, never()).save(any());
        verifyNoInteractions(activities, ticketSlaService, ticketOutboxService);
    }

    @Test
    void identifiedCreationRejectsAnonymousFieldsBeforePersistence() {
        CreateTicketRequest request = new CreateTicketRequest(1L, "Resumen", "Descripción", Map.of(), null,
                "chosen-password", null);

        assertThrows(InvalidTicketRequestException.class, () -> service.create(request, identity(), null));

        verifyNoInteractions(requestTypes, anonymousTicketCredentialService, anonymousContactValidator);
        verify(tickets, never()).save(any());
    }

    @Test
    void startReviewRecordsAdminAsActorTypeWhenActorIsAdmin() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setAssignedAgent(null);
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-1", UUID.randomUUID(), "Admin Uno", null, ModuleRole.ADMIN);
        ModuleUser adminUser = new ModuleUser();
        adminUser.setId(99L);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(moduleUsers.findByCitizenId(admin.citizenId())).thenReturn(Optional.of(adminUser));

        service.startReview(ticketId, admin);

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActorType()).isEqualTo(ActorType.ADMIN);
        assertThat(activityCaptor.getValue().getActorId()).isEqualTo(admin.citizenId().toString());
    }

    @Test
    void startReviewPreservesCriticalEscalation() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.CRITICAL);
        Instant escalatedAt = NOW.minusSeconds(60);
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(escalatedAt);
        ModuleUser agentUser = new ModuleUser();
        agentUser.setId(42L);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(moduleUsers.findByCitizenId(actor.citizenId())).thenReturn(Optional.of(agentUser));

        TicketResponse response = service.startReview(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(response.isEscalated()).isTrue();
        assertThat(response.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.CRITICAL_PRIORITY);
        assertThat(response.getEscalatedAt()).isEqualTo(escalatedAt);
        assertThat(ticket.getEscalatedAt()).isEqualTo(escalatedAt);
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

    @Test
    void administrativeLifecycleRejectsCitizenAreaResponsibleAndStaffOwnerWithoutMutating() {
        for (ModuleRole role : List.of(ModuleRole.CITIZEN, ModuleRole.AREA_RESPONSIBLE)) {
            Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
            AuthenticatedIdentity unauthorized = new AuthenticatedIdentity(
                    "subject-" + role, UUID.randomUUID(), role.name(), "M6", role);
            when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> service.startReview(ticketId, unauthorized))
                    .isInstanceOf(UnauthorizedTicketOperationException.class);

            ticket.setCurrentStatus(TicketStatus.IN_REVIEW);
            assertThatThrownBy(() -> service.correctClassification(ticketId, 20L, unauthorized))
                    .isInstanceOf(UnauthorizedTicketOperationException.class);
            assertThatThrownBy(() -> service.routeToArea(ticketId, unauthorized))
                    .isInstanceOf(UnauthorizedTicketOperationException.class);
        }

        Ticket owned = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        UUID ownerId = UUID.randomUUID();
        owned.setCitizenId(ownerId);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(owned));
        for (ModuleRole role : List.of(ModuleRole.AGENT, ModuleRole.ADMIN)) {
            AuthenticatedIdentity owner = new AuthenticatedIdentity(
                    "owner-" + role, ownerId, role.name(), null, role);
            assertThatThrownBy(() -> service.startReview(ticketId, owner))
                    .isInstanceOf(UnauthorizedTicketOperationException.class);
        }

        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
        verifyNoInteractions(ticketOutboxService);
    }

    // ==================================================================
    // ---- correctClassification (Sprint 2) ----
    // ==================================================================

    @Test
    void correctClassificationRecalculatesAreaAffectedCountFormTemplateAndPriorityFromNewRequestType() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        Instant slaStart = NOW.minus(Duration.ofHours(1));
        ticket.setCreatedAt(slaStart);
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
        TicketSla resolutionSla = new TicketSla();
        Instant dueAt = NOW.plus(Duration.ofHours(24));
        resolutionSla.setDueAt(dueAt);
        when(ticketSlaService.recalculateInitialResolutionCycle(ticket, NOW))
                .thenReturn(Optional.of(resolutionSla));

        TicketResponse response = service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getResponsibleAreaId()).isEqualTo("obras-hidraulicas");
        assertThat(ticket.getPublicId()).isEqualTo(originalPublicId);
        assertThat(ticket.getEstimatedAffectedCount()).isEqualTo(20_000);
        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.HIGH);
        assertThat(ticket.getFormTemplateId()).isEqualTo(99L);
        assertThat(response.getRequestTypeCode()).isEqualTo("FLOODING");
        assertThat(ticket.getFormData()).isEmpty();
        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActorType()).isEqualTo(ActorType.AGENT);
        assertThat(activityCaptor.getValue().getActorId()).isEqualTo(actor.citizenId().toString());
        verify(ticketSlaService).recalculateInitialResolutionCycle(ticket, NOW);
        verify(ticketSlaService, never()).startFirstResponseCycle(any(), any());
        verify(ticketSlaService, never()).completeFirstResponseCycle(any(), any());
        verify(ticketOutboxService).contentUpdated(ticket, dueAt, NOW);
    }

    @Test
    void correctClassificationRecordsAdminAsActorTypeWhenActorIsAdmin() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-1", UUID.randomUUID(), "Admin Uno", null, ModuleRole.ADMIN);
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.MEDIUM, new BigDecimal("0.1000"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        service.correctClassification(ticketId, 20L, admin);

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActorType()).isEqualTo(ActorType.ADMIN);
        assertThat(activityCaptor.getValue().getActorId()).isEqualTo(admin.citizenId().toString());
    }

    @Test
    void sameClassificationDoesNotWriteOrPublishContentUpdated() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        RequestType current = ticket.getRequestType();
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(current.getId())).thenReturn(Optional.of(current));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.correctClassification(ticketId, current.getId(), actor);

        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
        verify(ticketOutboxService, never()).contentUpdated(any(), any(), any());
        verify(ticketSlaService, never()).recalculateInitialResolutionCycle(any(), any());
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
        Instant originalEscalatedAt = NOW.minusSeconds(1800);
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(originalEscalatedAt);
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.LOW, Risk.LOW, new BigDecimal("0.1000"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.LOW);
        assertThat(ticket.isEscalated()).isTrue();
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.CRITICAL_PRIORITY);
        assertThat(ticket.getEscalatedAt()).isEqualTo(originalEscalatedAt);
    }

    @Test
    void correctClassificationActivatesCriticalEscalationOnceWithoutChangingStatus() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        RequestType newRequestType = requestTypeSprint2(20L, "CRITICAL_TYPE", "M6",
                Priority.CRITICAL, Risk.CRITICAL, new BigDecimal("0.1000"));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(3, 4);

        TicketResponse response = service.correctClassification(ticketId, 20L, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(ticket.isEscalated()).isTrue();
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.CRITICAL_PRIORITY);
        assertThat(ticket.getEscalatedAt()).isEqualTo(NOW);
        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(TicketActivity::getActionType)
                .containsExactly(ActivityType.REQUEST_TYPE_CHANGED, ActivityType.ESCALATED);
        assertThat(captor.getAllValues().get(1).getSequence()).isEqualTo(5);
        assertThat(captor.getAllValues().get(1).getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(captor.getAllValues().get(1).getActorId()).isNull();
    }

    @Test
    void correctClassificationDoesNotRestartExistingCriticalEscalation() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.CRITICAL);
        Instant originalEscalatedAt = NOW.minusSeconds(3600);
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(originalEscalatedAt);
        RequestType newRequestType = requestTypeSprint2(20L, "CRITICAL_TYPE", "M6",
                Priority.CRITICAL, Risk.CRITICAL, new BigDecimal("0.1000"));
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getEscalatedAt()).isEqualTo(originalEscalatedAt);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.CRITICAL_PRIORITY);
        verify(activities).save(argThat(activity -> activity.getActionType() == ActivityType.REQUEST_TYPE_CHANGED));
        verify(activities, never()).save(argThat(activity -> activity.getActionType() == ActivityType.ESCALATED));
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
     * QA - Story 2.3: mismo bloqueo de "acción staff en ticket propio" que
     * startReviewRejectsTriageOnActorsOwnTicket, acá para correctClassification.
     */
    @Test
    void correctClassificationRejectsTriageOnActorsOwnTicket() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.correctClassification(ticketId, 20L, actor))
                .isInstanceOf(UnauthorizedTicketOperationException.class);

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

    /**
     * QA FAIL (Sprint 2): la corrección de clasificación no publicaba
     * ticketUpdated/CONTENT_UPDATED al reclasificar un ticket identificado
     * (Eventos V1.69 §7.2/§7.7).
     */
    @Test
    void correctClassificationPublishesContentUpdatedEventForIdentifiedTicket() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        String originalPublicId = ticket.getPublicId();
        // QA: data.updatedAt no puede salir de ticket.getUpdatedAt() — es
        // @UpdateTimestamp (Hibernate) y todavía tiene el valor viejo en este
        // punto de la transacción (recién se refresca en el flush). Se fija
        // un valor viejo a propósito para que el test falle si se vuelve a
        // leer ese campo en vez de usar "now".
        ticket.setUpdatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.MEDIUM, new BigDecimal("0.1000"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        service.correctClassification(ticketId, 20L, actor);

        assertThat(ticket.getPublicId()).isEqualTo(originalPublicId);
        verify(ticketOutboxService, times(1)).contentUpdated(ticket, null, NOW);
    }

    /**
     * Eventos V1.69 §2.1 ("Anónimo · REGISTERED/IN_REVIEW/DUPLICATE" -&gt; sin
     * ticketUpdated): un ticket anónimo nunca tiene consumidor M1, así que la
     * reclasificación no debe publicar nada, a diferencia de routeToArea
     * (ver routeToAreaSkipsOutboxWhenAreaIsSelfManaged, que es un gate
     * distinto: ahí lo que importa es el área, no el anonimato).
     */
    @Test
    void correctClassificationSkipsOutboxEventForAnonymousTicket() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setAnonymous(true);
        RequestType newRequestType = requestTypeSprint2(20L, "FLOODING", "obras-hidraulicas",
                Priority.MEDIUM, new BigDecimal("0.1000"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(requestTypes.findById(20L)).thenReturn(Optional.of(newRequestType));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        service.correctClassification(ticketId, 20L, actor);

        verify(ticketOutboxService, times(1)).contentUpdated(ticket, null, NOW);
    }

    // ==================================================================
    // ---- routeToArea (Sprint 2) ----
    // ==================================================================

    @Test
    void routeToAreaMovesInReviewTicketToRoutedAndWritesRoutedEvent() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.HIGH);
        ticket.setResponsibleAreaId("M6");
        String originalPublicId = ticket.getPublicId();

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        TicketSla resolutionSla = new TicketSla();
        Instant dueAt = NOW.plus(Duration.ofHours(36));
        resolutionSla.setDueAt(dueAt);
        when(ticketSlaService.findLatestResolutionCycle(ticket)).thenReturn(Optional.of(resolutionSla));

        TicketResponse response = service.routeToArea(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(ticket.getPublicId()).isEqualTo(originalPublicId);
        assertThat(ticket.getClassificationFinalizedAt()).isNotNull();

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActionType()).isEqualTo(ActivityType.ROUTED);
        assertThat(activityCaptor.getValue().getActorType()).isEqualTo(ActorType.AGENT);
        assertThat(activityCaptor.getValue().getActorId()).isEqualTo(actor.citizenId().toString());

        verify(ticketOutboxService).routed(ticket, null, dueAt, NOW);
        verify(ticketOutboxService, never()).statusChanged(any(), any(), any());
    }

    @Test
    void routeToAreaPreservesEscalationAndIncludesItInOutboxSnapshot() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.CRITICAL);
        Instant escalatedAt = NOW.minusSeconds(600);
        ticket.setResponsibleAreaId("M6");
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(escalatedAt);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketResponse response = service.routeToArea(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(response.isEscalated()).isTrue();
        assertThat(response.getEscalatedAt()).isEqualTo(escalatedAt);
        verify(ticketOutboxService).routed(ticket, null, null, NOW);
    }

    @Test
    void routeToAreaStartsSelfManagedWorkWithoutRoutedActivityOrEvent() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId("M2");

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketResponse response = service.routeToArea(ticketId, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getCurrentStatus()).isNotEqualTo(TicketStatus.ROUTED);
        verify(activities).save(argThat(activity -> activity.getActionType() == ActivityType.STATE_CHANGED
                && activity.getNewStatus() == TicketStatus.IN_PROGRESS));
        verify(ticketOutboxService, never()).routed(any(), any(), any(), any());
        verify(ticketOutboxService).statusChanged(ticket, null, NOW);
    }

    @Test
    void routeToAreaOnWrongStateThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.routeToArea(ticketId, actor))
                .isInstanceOf(TicketStateConflictException.class);

        verifyNoInteractions(ticketOutboxService);
    }

    /**
     * QA - Story 2.3: mismo bloqueo de "acción staff en ticket propio" que
     * startReviewRejectsTriageOnActorsOwnTicket, acá para routeToArea.
     */
    @Test
    void routeToAreaRejectsTriageOnActorsOwnTicket() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId("M6");
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.routeToArea(ticketId, actor))
                .isInstanceOf(UnauthorizedTicketOperationException.class);

        verifyNoInteractions(ticketOutboxService);
        verify(tickets, never()).save(any());
    }

    /**
     * QA: mismo caso que startReviewRecordsAdminAsActorTypeWhenActorIsAdmin,
     * acá para routeToArea.
     */
    @Test
    void routeToAreaRecordsAdminAsActorTypeWhenActorIsAdmin() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId("M6");
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-1", UUID.randomUUID(), "Admin Uno", null, ModuleRole.ADMIN);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticketId)).thenReturn(0);
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.routeToArea(ticketId, admin);

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActorType()).isEqualTo(ActorType.ADMIN);
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

    @Test
    void routeToAreaAuditsAdminCapabilityAndCitizenId() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setResponsibleAreaId("M6");
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-subject", UUID.randomUUID(), "Admin", null, ModuleRole.ADMIN);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        service.routeToArea(ticketId, admin);

        verify(activities).save(argThat(activity -> activity.getActorType() == ActorType.ADMIN
                && admin.citizenId().toString().equals(activity.getActorId())
                && !admin.subjectId().equals(activity.getActorId())));
    }

    // ==================================================================
    // ---- cancelTicket ----
    // ==================================================================

    /**
     * Work item de equipo: "el único CANCELLED que funciona es el de la
     * simulación" — este endpoint cubre la cancelación temprana directa
     * (Entidades V1.49 §24: "Ciudadano owner / propietario anónimo
     * acreditado / AGENT / ADMIN"), antes de que el ticket llegue a
     * gestión externa.
     */
    @Test
    void cancelTicketByOwnerFromRegisteredPersistsCancellationAndPublishesOutboxEvent() {
        AuthenticatedIdentity owner = new AuthenticatedIdentity(
                "citizen-1", UUID.randomUUID(), "Vecino Uno", null, ModuleRole.CITIZEN);
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        ticket.setCitizenId(owner.citizenId());
        ticket.setAnonymous(false);
        // QA (mismo gap detectado en CONTENT_UPDATED/ROUTED): data.updatedAt
        // no puede salir de ticket.getUpdatedAt().
        ticket.setUpdatedAt(Instant.parse("2020-01-01T00:00:00Z"));

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);
        request.setPublicMessage("Ya no es necesario");

        TicketResponse response = service.cancelTicket(ticketId, request, owner);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);

        ArgumentCaptor<TicketCancellation> cancellationCaptor = ArgumentCaptor.forClass(TicketCancellation.class);
        verify(cancellationRepository).save(cancellationCaptor.capture());
        TicketCancellation cancellation = cancellationCaptor.getValue();
        assertThat(cancellation.getReasonCode()).isEqualTo(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);
        assertThat(cancellation.getCancelledByType()).isEqualTo(ActorType.CITIZEN);
        assertThat(cancellation.getPublicMessage()).isEqualTo("Ya no es necesario");

        ArgumentCaptor<TicketActivity> activityCaptor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities).save(activityCaptor.capture());
        assertThat(activityCaptor.getValue().getActionType()).isEqualTo(ActivityType.CANCELLED);
        assertThat(activityCaptor.getValue().getActorType()).isEqualTo(ActorType.CITIZEN);

        assertThat(cancellation.getCancelledById()).isEqualTo(owner.citizenId().toString());
        assertThat(activityCaptor.getValue().getActorId()).isEqualTo(owner.citizenId().toString());
        verify(ticketSlaService).terminateActiveCycles(ticket, NOW);
        verify(ticketOutboxService, times(1)).cancelled(ticket,
                CancellationReasonCode.WITHDRAWN_BY_CITIZEN, "Ya no es necesario", true, NOW);
    }

    @Test
    void anonymousExternalOwnerCancelsRegisteredTicketWithoutPublishingPreRoutedEvent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        ticket.setAnonymous(true);
        ticket.setCitizenId(null);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);
        request.setPublicMessage("Retiro anónimo");

        TicketResponse response = service.cancelAnonymousTicket(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
        verify(cancellationRepository).save(argThat(cancellation ->
                cancellation.getCancelledByType() == ActorType.CITIZEN
                        && cancellation.getCancelledById() == null));
        verify(activities).save(argThat(activity -> activity.getActorType() == ActorType.CITIZEN
                && activity.getActorId() == null));
        verify(ticketSlaService).terminateActiveCycles(ticket, NOW);
        verify(ticketOutboxService, never()).cancelled(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void anonymousSelfManagedOwnerCancelsRegisteredTicketWithoutPublishingEvent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        ticket.setAnonymous(true);
        ticket.setCitizenId(null);
        ticket.setResponsibleAreaId("M2");
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);

        TicketResponse response = service.cancelAnonymousTicket(ticketId, request);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
        verify(cancellationRepository).save(any(TicketCancellation.class));
        verify(ticketSlaService).terminateActiveCycles(ticket, NOW);
        verify(ticketOutboxService, never()).cancelled(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void anonymousOwnerCancellationUsesTheExistingRegisteredOnlyRule() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setAnonymous(true);
        ticket.setCitizenId(null);
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);

        assertThatThrownBy(() -> service.cancelAnonymousTicket(ticketId, request))
                .isInstanceOf(TicketStateConflictException.class);
        verify(cancellationRepository, never()).save(any());
        verify(activities, never()).save(any());
    }

    @Test
    void cancelTicketByOwnerFromInReviewIsRejectedWithoutEffects() {
        AuthenticatedIdentity owner = new AuthenticatedIdentity(
                "citizen-1", UUID.randomUUID(), "Vecino Uno", null, ModuleRole.CITIZEN);
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setCitizenId(owner.citizenId());
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);

        assertThatThrownBy(() -> service.cancelTicket(ticketId, request, owner))
                .isInstanceOf(TicketStateConflictException.class);

        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        verifyNoInteractions(cancellationRepository, informationRequestService, ticketSlaService,
                activities, ticketOutboxService);
        verify(tickets, never()).save(any());
    }

    @Test
    void cancelTicketByOwnerFromPendingInformationIsRejectedWithoutEffects() {
        AuthenticatedIdentity owner = new AuthenticatedIdentity(
                "citizen-1", UUID.randomUUID(), "Vecino Uno", null, ModuleRole.CITIZEN);
        Ticket ticket = ticket(TicketStatus.PENDING_INFORMATION, Priority.LOW);
        ticket.setCitizenId(owner.citizenId());
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);

        assertThatThrownBy(() -> service.cancelTicket(ticketId, request, owner))
                .isInstanceOf(TicketStateConflictException.class);

        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.PENDING_INFORMATION);
        verifyNoInteractions(cancellationRepository, informationRequestService, ticketSlaService,
                activities, ticketOutboxService);
        verify(tickets, never()).save(any());
    }

    @Test
    void cancelTicketTreatsInternalRoleOwnersAsCitizensAfterRegistered() {
        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);

        for (ModuleRole role : List.of(ModuleRole.AGENT, ModuleRole.ADMIN, ModuleRole.AREA_RESPONSIBLE)) {
            UUID ownerId = UUID.randomUUID();
            AuthenticatedIdentity owner = new AuthenticatedIdentity(
                    "owner-" + role, ownerId, role.name(), "area-obras", role);
            Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
            ticket.setCitizenId(ownerId);
            when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> service.cancelTicket(ticketId, request, owner))
                    .isInstanceOf(TicketStateConflictException.class);
            assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        }

        verify(cancellationRepository, never()).save(any());
        verify(tickets, never()).save(any());
        verifyNoInteractions(informationRequestService, ticketSlaService, activities, ticketOutboxService);
    }

    @Test
    void cancelTicketByAgentOnOthersTicketFromPendingInformationSucceeds() {
        Ticket ticket = ticket(TicketStatus.PENDING_INFORMATION, Priority.LOW);
        ticket.setCitizenId(UUID.randomUUID());

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.OUT_OF_SCOPE);

        TicketResponse response = service.cancelTicket(ticketId, request, actor);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
        ArgumentCaptor<TicketCancellation> cancellationCaptor = ArgumentCaptor.forClass(TicketCancellation.class);
        verify(cancellationRepository).save(cancellationCaptor.capture());
        assertThat(cancellationCaptor.getValue().getCancelledByType()).isEqualTo(ActorType.AGENT);
        assertThat(cancellationCaptor.getValue().getCancelledById()).isEqualTo(actor.citizenId().toString());
        verify(informationRequestService).cancelPendingBecauseTicketTerminated(ticket);
        verify(ticketSlaService).terminateActiveCycles(ticket, NOW);
        verify(ticketOutboxService).cancelled(ticket, CancellationReasonCode.OUT_OF_SCOPE, null, true, NOW);
    }

    @Test
    void cancelTicketByAdminOnOthersTicketFromInReviewAndPendingInformationSucceeds() {
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-1", UUID.randomUUID(), "Admin Uno", null, ModuleRole.ADMIN);
        Ticket inReview = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        inReview.setCitizenId(UUID.randomUUID());
        Ticket pendingInformation = ticket(TicketStatus.PENDING_INFORMATION, Priority.LOW);
        pendingInformation.setCitizenId(UUID.randomUUID());
        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(inReview), Optional.of(pendingInformation));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.OUT_OF_SCOPE);

        TicketResponse first = service.cancelTicket(ticketId, request, admin);
        TicketResponse second = service.cancelTicket(ticketId, request, admin);

        assertThat(first.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(second.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
        verify(cancellationRepository, times(2)).save(argThat(cancellation ->
                cancellation.getCancelledByType() == ActorType.ADMIN));
        verify(informationRequestService).cancelPendingBecauseTicketTerminated(pendingInformation);
        verify(ticketSlaService).terminateActiveCycles(inReview, NOW);
        verify(ticketSlaService).terminateActiveCycles(pendingInformation, NOW);
    }

    @Test
    void cancelTicketRejectsNonOwnerCitizen() {
        AuthenticatedIdentity otherCitizen = new AuthenticatedIdentity(
                "citizen-2", UUID.randomUUID(), "Vecino Dos", null, ModuleRole.CITIZEN);
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        ticket.setCitizenId(UUID.randomUUID());

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);

        assertThatThrownBy(() -> service.cancelTicket(ticketId, request, otherCitizen))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        verify(cancellationRepository, never()).save(any());
    }

    /**
     * AREA_RESPONSIBLE no está en la lista de roles habilitados por
     * Entidades V1.49 §24 ("Ciudadano owner / propietario anónimo
     * acreditado / AGENT / ADMIN") a diferencia de GET /staff/tickets/{id}
     * — no hereda acceso staff a este endpoint sobre un ticket ajeno.
     */
    @Test
    void cancelTicketRejectsAreaResponsibleOnOthersTicket() {
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area-1", UUID.randomUUID(), "Responsable Uno", "obras-viales", ModuleRole.AREA_RESPONSIBLE);
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.LOW);
        ticket.setCitizenId(UUID.randomUUID());

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.OUT_OF_SCOPE);

        assertThatThrownBy(() -> service.cancelTicket(ticketId, request, areaResponsible))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
    }

    /**
     * ROUTED/IN_PROGRESS quedan afuera a propósito: esa cancelación llega
     * por el flujo de integración (updateTicketStatus/REJECTED), no por
     * este endpoint — ver TicketStatusUpdateService.
     */
    @Test
    void cancelTicketRejectsWhenTicketAlreadyRouted() {
        Ticket ticket = ticket(TicketStatus.ROUTED, Priority.LOW);
        ticket.setCitizenId(UUID.randomUUID());

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.OUT_OF_SCOPE);

        assertThatThrownBy(() -> service.cancelTicket(ticketId, request, actor))
                .isInstanceOf(TicketStateConflictException.class);
        verify(cancellationRepository, never()).save(any());
    }

    /** La cancelación administrativa conserva el gate central del outbox. */
    @Test
    void administrativeCancellationKeepsDelegatingPublicationPolicyToOutboxService() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.LOW);
        ticket.setAnonymous(true);

        when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(activities.countByTicketId(ticketId)).thenReturn(0);

        CancelTicketRequest request = new CancelTicketRequest();
        request.setReasonCode(CancellationReasonCode.OUT_OF_SCOPE);

        service.cancelTicket(ticketId, request, actor);

        verify(ticketOutboxService).cancelled(ticket, CancellationReasonCode.OUT_OF_SCOPE, null, true, NOW);
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
        Instant firstResponseDueAt = NOW.plus(Duration.ofHours(4));
        TicketSla firstResponseSla = sla(SlaType.FIRST_RESPONSE, SlaStatus.NEAR_DUE,
                firstResponseDueAt.minus(Duration.ofHours(1)), firstResponseDueAt);
        Instant resolutionDueAt = NOW.plus(Duration.ofDays(2));
        TicketSla resolutionSla = sla(SlaType.RESOLUTION, SlaStatus.BREACHED,
                resolutionDueAt.minus(Duration.ofHours(4)), resolutionDueAt);

        when(tickets.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(locations.findAllByTicket_IdIn(anyList())).thenReturn(List.of(location));
        when(ticketSlaService.findLatestFirstResponseCycles(page.getContent()))
                .thenReturn(Map.of(ticketId, firstResponseSla));
        when(ticketSlaService.findLatestResolutionCycles(page.getContent()))
                .thenReturn(Map.of(ticketId, resolutionSla));

        Page<TicketResponse> result = service.listTickets(filter, pageable);

        assertThat(result.getContent()).hasSize(1);
        TicketResponse response = result.getContent().get(0);
        assertThat(response.getNeighborhoodName()).isEqualTo("Recoleta");
        assertThat(response.getFirstResponseDueAt()).isEqualTo(firstResponseDueAt);
        assertThat(response.isFirstResponseNearDue()).isTrue();
        assertThat(response.isFirstResponseBreached()).isFalse();
        assertThat(response.getResolutionDueAt()).isEqualTo(resolutionDueAt);
        assertThat(response.isSlaNearDue()).isFalse();
        assertThat(response.isSlaBreached()).isTrue();
        verify(ticketSlaService).findLatestResolutionCycles(page.getContent());
        verify(ticketSlaService).findLatestFirstResponseCycles(page.getContent());
        verify(ticketSlaService, never()).findLatestResolutionCycle(any());
        verify(ticketSlaService, never()).findLatestFirstResponseCycle(any());
        verify(attachmentRepository, never()).findAllByTicket_IdOrderByCreatedAtAsc(any());
        verify(attachmentRepository, never())
                .findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(any(), any());
        verify(activities, never()).findAllByTicket_IdOrderBySequenceAsc(any());
    }

    @Test
    void listTicketsLeavesNeighborhoodNullWhenTicketHasNoLocation() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);

        TicketFilter filter = new TicketFilter(null, null, null, null, null);
        Pageable pageable = Pageable.unpaged();
        Page<Ticket> page = new PageImpl<>(List.of(ticket));

        when(tickets.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(locations.findAllByTicket_IdIn(anyList())).thenReturn(List.of());
        when(ticketSlaService.findLatestFirstResponseCycles(page.getContent())).thenReturn(Map.of());
        when(ticketSlaService.findLatestResolutionCycles(page.getContent())).thenReturn(Map.of());

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
    // ---- listMyTickets (GET /me/tickets) ----
    // ==================================================================

    /**
     * A diferencia de listTickets (bandeja staff, Specification con
     * filtros), listMyTickets siempre scopea por el citizenId de quien
     * pregunta y no acepta ningún filtro adicional.
     */
    @Test
    void listMyTicketsScopesToCallerCitizenIdAndMapsNeighborhoodFromBatchedLocations() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Recoleta");
        neighborhood.setPopulation(150_000);
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setNeighborhood(neighborhood);

        Pageable pageable = Pageable.unpaged();
        Page<Ticket> page = new PageImpl<>(List.of(ticket));

        when(tickets.findByCitizenId(eq(actor.citizenId()), any(Pageable.class))).thenReturn(page);
        when(locations.findAllByTicket_IdIn(anyList())).thenReturn(List.of(location));
        when(ticketSlaService.findLatestFirstResponseCycles(page.getContent())).thenReturn(Map.of());
        when(ticketSlaService.findLatestResolutionCycles(page.getContent())).thenReturn(Map.of());

        Page<TicketResponse> result = service.listMyTickets(actor, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getNeighborhoodName()).isEqualTo("Recoleta");
        verify(tickets).findByCitizenId(eq(actor.citizenId()), any(Pageable.class));
        verify(tickets, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(ticketSlaService).findLatestFirstResponseCycles(page.getContent());
        verify(ticketSlaService).findLatestResolutionCycles(page.getContent());
        verify(ticketSlaService, never()).findLatestFirstResponseCycle(any());
        verify(ticketSlaService, never()).findLatestResolutionCycle(any());
        verify(attachmentRepository, never()).findAllByTicket_IdOrderByCreatedAtAsc(any());
        verify(attachmentRepository, never())
                .findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(any(), any());
        verify(activities, never()).findAllByTicket_IdOrderBySequenceAsc(any());
    }

    @Test
    void listMyTicketsWithInvalidSortFieldThrowsBadRequestWithoutQueryingRepository() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("notAField"));

        assertThatThrownBy(() -> service.listMyTickets(actor, pageable))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(tickets, never()).findByCitizenId(any(), any());
    }

    // ==================================================================
    // ---- getById (GET /tickets/{id}) ----
    // ==================================================================

    @Test
    void getByIdReturnsTheTicketWhenTheCallerIsItsOwner() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getById(ticketId, actor);

        assertThat(response.getId()).isEqualTo(ticketId);
    }

    @Test
    void getByIdReturnsPublicDetailWithSanitizedTimeline() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);
        ticket.setDescription("Descripción completa");

        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        neighborhood.setName("Recoleta");
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setNeighborhood(neighborhood);

        Attachment publicAttachment = attachment(1L, MessageVisibility.PUBLIC, "foto.jpg");
        TicketActivity returned = activity(2, ActivityType.RETURNED_BY_AREA, "INTERNAL_REASON",
                "mensaje interno");
        returned.setActorId("actor-secreto");
        returned.setSourceModuleId("M9");
        returned.setExternalEventId(UUID.randomUUID());
        returned.setMetadata(Map.of("secret", true));
        returned.setTicketVersion(7);
        TicketActivity internalMessage = activity(3, ActivityType.INTERNAL_MESSAGE_ADDED, null,
                "nota interna");

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.of(location));
        when(attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                ticketId, MessageVisibility.PUBLIC)).thenReturn(List.of(publicAttachment));
        when(activities.findAllByTicket_IdOrderBySequenceAsc(ticketId))
                .thenReturn(List.of(returned, internalMessage));

        TicketDetailResponse response = service.getById(ticketId, actor);

        assertThat(response.getDescription()).isEqualTo("Descripción completa");
        assertThat(response.getNeighborhoodName()).isEqualTo("Recoleta");
        assertThat(response.getAttachments()).extracting("fileName", "visibility")
                .containsExactly(tuple("foto.jpg", MessageVisibility.PUBLIC));
        assertThat(response.getTicketActivities()).hasSize(1);
        assertThat(response.getTicketActivities().getFirst().getSequence()).isEqualTo(2);
        assertThat(response.getTicketActivities().getFirst().getOccurredAt()).isEqualTo(NOW);
        assertThat(response.getTicketActivities().getFirst().getReasonCode()).isNull();
        assertThat(response.getTicketActivities().getFirst().getActorType()).isNull();
        assertThat(response.getTicketActivities().getFirst().getMessage()).isNull();
        verify(attachmentRepository, never()).findAllByTicket_IdOrderByCreatedAtAsc(any());
    }

    @Test
    void getByIdProjectsFirstResponseStateWithoutInferringFromDeadlines() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);
        Instant dueAt = NOW.minus(Duration.ofHours(1));
        TicketSla firstResponseSla = sla(SlaType.FIRST_RESPONSE, SlaStatus.RUNNING,
                dueAt.minus(Duration.ofHours(1)), dueAt);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(ticketSlaService.findLatestResolutionCycle(ticket)).thenReturn(Optional.empty());

        for (SlaStatus status : List.of(SlaStatus.RUNNING, SlaStatus.NEAR_DUE, SlaStatus.MET,
                SlaStatus.BREACHED, SlaStatus.STOPPED)) {
            firstResponseSla.setStatus(status);
            firstResponseSla.setCompletedAt(status == SlaStatus.BREACHED ? NOW : null);
            when(ticketSlaService.findLatestFirstResponseCycle(ticket))
                    .thenReturn(Optional.of(firstResponseSla));

            TicketDetailResponse response = service.getById(ticketId, actor);

            assertThat(response.getFirstResponseDueAt()).as(status.name()).isEqualTo(dueAt);
            assertThat(response.isFirstResponseNearDue()).as(status.name())
                    .isEqualTo(status == SlaStatus.NEAR_DUE);
            assertThat(response.isFirstResponseBreached()).as(status.name())
                    .isEqualTo(status == SlaStatus.BREACHED);
        }
    }

    @Test
    void getByIdProjectsResolutionDeadlineAndExistingSignals() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS, Priority.HIGH);
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);
        Instant dueAt = NOW.plus(Duration.ofHours(8));
        Instant nearDueAt = NOW.plus(Duration.ofHours(2));
        TicketSla resolutionSla = sla(SlaType.RESOLUTION, SlaStatus.NEAR_DUE, nearDueAt, dueAt);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(ticketSlaService.findLatestFirstResponseCycle(ticket)).thenReturn(Optional.empty());
        when(ticketSlaService.findLatestResolutionCycle(ticket)).thenReturn(Optional.of(resolutionSla));

        TicketDetailResponse response = service.getById(ticketId, actor);

        assertThat(response.getResolutionDueAt()).isEqualTo(dueAt);
        assertThat(response.getResolutionNearDueAt()).isEqualTo(nearDueAt);
        assertThat(response.isSlaNearDue()).isTrue();
        assertThat(response.isSlaBreached()).isFalse();
    }

    @Test
    void getByIdUsesNullDeadlinesAndFalseFlagsWhenSlaCyclesAreAbsent() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setCitizenId(actor.citizenId());
        ticket.setAnonymous(false);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(ticketSlaService.findLatestFirstResponseCycle(ticket)).thenReturn(Optional.empty());
        when(ticketSlaService.findLatestResolutionCycle(ticket)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getById(ticketId, actor);

        assertThat(response.getFirstResponseDueAt()).isNull();
        assertThat(response.isFirstResponseNearDue()).isFalse();
        assertThat(response.isFirstResponseBreached()).isFalse();
        assertThat(response.getResolutionDueAt()).isNull();
        assertThat(response.getResolutionNearDueAt()).isNull();
        assertThat(response.isSlaNearDue()).isFalse();
        assertThat(response.isSlaBreached()).isFalse();
    }

    @Test
    void getByIdOnMissingTicketThrowsResourceNotFound() {
        when(tickets.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(ticketId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByIdOnSomeoneElsesTicketThrowsUnauthorized() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setAnonymous(false);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getById(ticketId, actor))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        verify(locations, never()).findByTicket_Id(any());
    }

    /**
     * Un ticket anónimo no tiene citizenId, así que nunca es "propio" de
     * ningún autenticado, aunque coincidencialmente algún campo calzara.
     */
    @Test
    void getByIdOnAnonymousTicketThrowsUnauthorizedEvenWithoutACitizenId() {
        Ticket ticket = ticket(TicketStatus.REGISTERED, Priority.MEDIUM);
        ticket.setCitizenId(null);
        ticket.setAnonymous(true);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getById(ticketId, actor))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
    }

    // ==================================================================
    // ---- getStaffDetail (GET /staff/tickets/{id}) ----
    // ==================================================================

    @Test
    void getStaffDetailAllowsAgentOnATicketFromAnyArea() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        // fixture ticket() usa responsibleAreaId="obras-viales"; actor es AGENT de "area-obras".
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getStaffDetail(ticketId, actor);

        assertThat(response.getId()).isEqualTo(ticketId);
    }

    @Test
    void getStaffDetailShowsAnonymousContactToAgentAndAdmin() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setCitizenId(null);
        ticket.setAnonymous(true);
        ticket.setAnonymousContactChannel(AnonymousContactChannel.EMAIL);
        ticket.setAnonymousContactValue("private@example.test");
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-1", UUID.randomUUID(), "Admin Uno", null, ModuleRole.ADMIN);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        for (AuthenticatedIdentity viewer : List.of(actor, admin)) {
            StaffTicketDetailResponse response = service.getStaffDetail(ticketId, viewer);

            assertThat(response.getAnonymousContact()).isNotNull();
            assertThat(response.getAnonymousContact().channel()).isEqualTo(AnonymousContactChannel.EMAIL);
            assertThat(response.getAnonymousContact().value()).isEqualTo("private@example.test");
            assertThat(ticket.toString()).doesNotContain("EMAIL", "private@example.test");
            assertThat(response.toString()).doesNotContain("EMAIL", "private@example.test");
            assertThat(response.getAnonymousContact().toString()).doesNotContain("EMAIL", "private@example.test");
        }
    }

    @Test
    void getStaffDetailHidesAnonymousContactFromAreaResponsible() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setCitizenId(null);
        ticket.setAnonymous(true);
        ticket.setResponsibleAreaId("obras-viales");
        ticket.setAnonymousContactChannel(AnonymousContactChannel.PHONE);
        ticket.setAnonymousContactValue("+5491112345678");
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area-1", UUID.randomUUID(), "Responsable Uno", "obras-viales", ModuleRole.AREA_RESPONSIBLE);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        StaffTicketDetailResponse response = service.getStaffDetail(ticketId, areaResponsible);

        assertThat(response.getId()).isEqualTo(ticketId);
        assertThat(response.getAnonymousContact()).isNull();
    }

    @Test
    void getStaffDetailOmitsAnonymousContactForIdentifiedTicketsAndAnonymousTicketsWithoutContact() {
        Ticket identified = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        identified.setAnonymous(false);
        identified.setCitizenId(UUID.randomUUID());
        when(tickets.findById(ticketId)).thenReturn(Optional.of(identified));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        assertThat(service.getStaffDetail(ticketId, actor).getAnonymousContact()).isNull();

        Ticket anonymousWithoutContact = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        anonymousWithoutContact.setAnonymous(true);
        anonymousWithoutContact.setCitizenId(null);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(anonymousWithoutContact));

        assertThat(service.getStaffDetail(ticketId, actor).getAnonymousContact()).isNull();
    }

    @Test
    void getStaffDetailReturnsAllAttachmentVisibilitiesAndStaffActivityFields() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setDescription("Descripción staff");
        Attachment publicAttachment = attachment(1L, MessageVisibility.PUBLIC, "publico.pdf");
        Attachment internalAttachment = attachment(2L, MessageVisibility.INTERNAL, "interno.pdf");
        TicketActivity activity = activity(1, ActivityType.PRIORITY_CHANGED, "STAFF_REASON", "nota staff");
        activity.setActorType(ActorType.AGENT);
        activity.setPreviousPriority(Priority.LOW);
        activity.setNewPriority(Priority.HIGH);
        Instant persistedAt = NOW.minusSeconds(30);
        activity.setOccurredAt(null);
        activity.setCreatedAt(persistedAt);

        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(attachmentRepository.findAllByTicket_IdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(publicAttachment, internalAttachment));
        when(activities.findAllByTicket_IdOrderBySequenceAsc(ticketId)).thenReturn(List.of(activity));

        TicketDetailResponse response = service.getStaffDetail(ticketId, actor);

        assertThat(response.getDescription()).isEqualTo("Descripción staff");
        assertThat(response.getAttachments()).extracting("visibility")
                .containsExactly(MessageVisibility.PUBLIC, MessageVisibility.INTERNAL);
        assertThat(response.getTicketActivities().getFirst().getActorType()).isEqualTo(ActorType.AGENT);
        assertThat(response.getTicketActivities().getFirst().getPreviousPriority()).isEqualTo(Priority.LOW);
        assertThat(response.getTicketActivities().getFirst().getNewPriority()).isEqualTo(Priority.HIGH);
        assertThat(response.getTicketActivities().getFirst().getOccurredAt()).isEqualTo(persistedAt);
        assertThat(response.getTicketActivities().getFirst().getReasonCode()).isEqualTo("STAFF_REASON");
        assertThat(response.getTicketActivities().getFirst().getMessage()).isEqualTo("nota staff");
    }

    @Test
    void getStaffDetailAllowsAdminOnATicketFromAnyArea() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        AuthenticatedIdentity admin = new AuthenticatedIdentity(
                "admin-1", UUID.randomUUID(), "Admin Uno", null, ModuleRole.ADMIN);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getStaffDetail(ticketId, admin);

        assertThat(response.getId()).isEqualTo(ticketId);
    }

    @Test
    void getStaffDetailAllowsAreaResponsibleWhenTheTicketBelongsToTheirArea() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setResponsibleAreaId("obras-viales");
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area-1", UUID.randomUUID(), "Responsable Uno", "obras-viales", ModuleRole.AREA_RESPONSIBLE);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getStaffDetail(ticketId, areaResponsible);

        assertThat(response.getId()).isEqualTo(ticketId);
    }

    @Test
    void getStaffDetailRejectsAreaResponsibleFromADifferentAreaOnSomeoneElsesTicket() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setResponsibleAreaId("obras-viales");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setAnonymous(false);
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area-1", UUID.randomUUID(), "Responsable Uno", "otra-area", ModuleRole.AREA_RESPONSIBLE);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getStaffDetail(ticketId, areaResponsible))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        verify(locations, never()).findByTicket_Id(any());
    }

    /**
     * Guía funcional M2 §7.1: si el usuario interno es owner del ticket
     * (aunque sea de otra área), igual puede ver el detalle staff.
     */
    @Test
    void getStaffDetailAllowsAreaResponsibleOnTheirOwnTicketEvenFromADifferentArea() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setResponsibleAreaId("obras-viales");
        UUID citizenId = UUID.randomUUID();
        ticket.setCitizenId(citizenId);
        ticket.setAnonymous(false);
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area-1", citizenId, "Responsable Uno", "otra-area", ModuleRole.AREA_RESPONSIBLE);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getStaffDetail(ticketId, areaResponsible);

        assertThat(response.getId()).isEqualTo(ticketId);
    }

    @Test
    void getStaffDetailOnMissingTicketThrowsResourceNotFound() {
        when(tickets.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStaffDetail(ticketId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================================================================
    // ---- getStaffCitizenView (GET /staff/tickets/{id}/citizen-view) ----
    // ==================================================================

    /**
     * getStaffCitizenView delega directo en getStaffDetail (misma
     * proyección hoy, ver el javadoc del método); estos tests sólo
     * verifican que esa delegación funciona — la matriz completa de
     * autorización ya está cubierta arriba, sobre getStaffDetail.
     */
    @Test
    void getStaffCitizenViewReturnsTheTicketWhenAccessIsAllowed() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        TicketDetailResponse response = service.getStaffCitizenView(ticketId, actor);

        assertThat(response.getId()).isEqualTo(ticketId);
    }

    @Test
    void getStaffCitizenViewUsesThePublicProjection() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        Attachment publicAttachment = attachment(1L, MessageVisibility.PUBLIC, "publico.pdf");
        TicketActivity activity = activity(1, ActivityType.PROGRESS_REPORTED, "INTERNAL_REASON",
                "mensaje interno");
        Instant persistedAt = NOW.minusSeconds(60);
        activity.setOccurredAt(null);
        activity.setCreatedAt(persistedAt);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(locations.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        when(attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                ticketId, MessageVisibility.PUBLIC)).thenReturn(List.of(publicAttachment));
        when(activities.findAllByTicket_IdOrderBySequenceAsc(ticketId)).thenReturn(List.of(activity));

        TicketDetailResponse response = service.getStaffCitizenView(ticketId, actor);

        assertThat(response.getAttachments()).extracting("visibility")
                .containsExactly(MessageVisibility.PUBLIC);
        assertThat(response.getTicketActivities().getFirst().getActorType()).isNull();
        assertThat(response.getTicketActivities().getFirst().getReasonCode()).isNull();
        assertThat(response.getTicketActivities().getFirst().getMessage()).isNull();
        assertThat(response.getTicketActivities().getFirst().getOccurredAt()).isEqualTo(persistedAt);
        verify(attachmentRepository, never()).findAllByTicket_IdOrderByCreatedAtAsc(any());
    }

    @Test
    void getStaffCitizenViewRejectsAreaResponsibleFromADifferentAreaOnSomeoneElsesTicket() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW, Priority.MEDIUM);
        ticket.setResponsibleAreaId("obras-viales");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setAnonymous(false);
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area-1", UUID.randomUUID(), "Responsable Uno", "otra-area", ModuleRole.AREA_RESPONSIBLE);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getStaffCitizenView(ticketId, areaResponsible))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
    }

    @Test
    void getStaffCitizenViewOnMissingTicketThrowsResourceNotFound() {
        when(tickets.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStaffCitizenView(ticketId, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================================================================
    // ---- fixtures Sprint 2 ----
    // ==================================================================

    private TicketSla sla(SlaType type, SlaStatus status, Instant nearDueAt, Instant dueAt) {
        TicketSla sla = new TicketSla();
        sla.setSlaType(type);
        sla.setStatus(status);
        sla.setNearDueAt(nearDueAt);
        sla.setDueAt(dueAt);
        return sla;
    }

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

    private Attachment attachment(Long id, MessageVisibility visibility, String fileName) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setFileName(fileName);
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(123L);
        attachment.setVisibility(visibility);
        attachment.setCreatedAt(NOW);
        return attachment;
    }

    private TicketActivity activity(int sequence, ActivityType type, String reasonCode, String message) {
        TicketActivity activity = new TicketActivity();
        activity.setSequence(sequence);
        activity.setActionType(type);
        activity.setPreviousStatus(TicketStatus.IN_PROGRESS);
        activity.setNewStatus(TicketStatus.IN_REVIEW);
        activity.setActorType(ActorType.AGENT);
        activity.setReasonCode(reasonCode);
        activity.setMessage(message);
        activity.setOccurredAt(NOW);
        return activity;
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
