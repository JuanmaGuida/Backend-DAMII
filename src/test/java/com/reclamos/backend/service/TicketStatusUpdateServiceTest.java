package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.UpdateTicketStatusEnvelope;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.dto.request.ReopenTicketRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.InboxEvent;
import com.reclamos.backend.entity.InboxStatus;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketResolution;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.entity.UpdateTicketStatusType;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.InboxEventRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * applyUpdate recibe el {@link UpdateTicketStatusEnvelope} completo, no el
 * payload plano de {@code data}: cubre validación de envelope, dedupe por
 * eventId, validación de data.ticketId, y trazabilidad
 * (externalEventId/updateOccurredAt) persistida en TicketActivity.
 * <p>
 * El actor de estos eventos llega desde otro módulo (M6) vía integración,
 * así que su updatedBy.type es EXTERNAL_USER (Eventos §5.2, ejemplos de
 * §8.2/§8.3).
 */
@ExtendWith(MockitoExtension.class)
class TicketStatusUpdateServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketActivityRepository activityRepository;
    @Mock
    private TicketLocationRepository locationRepository;
    @Mock
    private TicketMessageRepository messageRepository;
    @Mock
    private InboxEventRepository inboxEventRepository;
    @Mock
    private TicketResolutionRepository resolutionRepository;

    private TicketStatusUpdateService service;
    private TicketResolutionService resolutionService;

    private final UUID ticketId = UUID.randomUUID();
    private final UpdateTicketStatusRequest.Actor externalActor =
            new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", "USR-M6-77");

    @BeforeEach
    void setUp() {
        resolutionService = new TicketResolutionService(
                ticketRepository, resolutionRepository, activityRepository,
                Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC), Duration.ofHours(72));
        service = new TicketStatusUpdateService(ticketRepository, activityRepository, locationRepository,
                messageRepository, inboxEventRepository, resolutionService);
        // Default para los tests que no ejercitan dedupe en sí: "eventId nunca visto".
        // Los tests de dedupe pisan este stub explícitamente.
        lenient().when(inboxEventRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(resolutionRepository.save(any())).thenAnswer(invocation -> {
            TicketResolution resolution = invocation.getArgument(0);
            resolution.setId(UUID.randomUUID());
            return resolution;
        });
    }

    @Test
    void startedMovesRoutedTicketToInProgressAndRecordsTraceability() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        Instant realOccurredAt = Instant.now().minusSeconds(120);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, "Comenzamos a trabajar en esto.", null,
                null, null, externalActor, realOccurredAt);
        UpdateTicketStatusEnvelope envelope = envelope(data);

        TicketResponse response = service.applyUpdate(ticketId, envelope);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(messageRepository).save(any());

        // Verifica que externalEventId y occurredAt queden persistidos en TicketActivity.
        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(captor.capture());
        assertThat(captor.getValue().getActionType()).isEqualTo(ActivityType.STATE_CHANGED);
        assertThat(captor.getValue().getExternalEventId()).isEqualTo(envelope.eventId());
        assertThat(captor.getValue().getOccurredAt()).isEqualTo(realOccurredAt);

        verify(inboxEventRepository).save(any());
    }

    @Test
    void startedOnWrongStateThrowsConflict() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(TicketStateConflictException.class);
    }

    @Test
    void progressWithoutAnyContentIsRejected() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.PROGRESS, null, null, null, null, externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void progressUpdatesCurrentProgressAndStaysInProgress() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(1);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.PROGRESS, null, null, 40, null, externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getCurrentProgress()).isEqualTo((short) 40);
    }

    @Test
    void informationRequiredWithoutMessageForCitizenIsRejected() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.INFORMATION_REQUIRED, null, null, null,
                new UpdateTicketStatusRequest.Details(null, null, null, null),
                externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void informationRequiredMovesTicketToPendingInformation() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                new UpdateTicketStatusRequest.InformationRequest("Indique la altura aproximada.", null),
                null, null, null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.INFORMATION_REQUIRED, null, null, null, details,
                externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.PENDING_INFORMATION);
    }

    @Test
    void returnedMovesTicketBackToInReview() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, new UpdateTicketStatusRequest.ReturnInfo("REQUEST_TYPE_MISMATCH"), null, null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RETURNED, null, "No corresponde a nuestra área.", null, details,
                externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);

        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(captor.capture());
        assertThat(captor.getValue().getReasonCode()).isEqualTo("REQUEST_TYPE_MISMATCH");
    }

    @Test
    void resolvedRequiresPublicMessage() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, null, "Se cambió la lámpara.", null, details,
                externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void resolvedFromInProgressPersistsResolutionActivityAndConfirmationDeadline() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(2);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED), null);
        Instant resolvedAt = Instant.parse("2026-09-08T09:30:00Z");
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "La luminaria fue reparada.", "Se reemplazó el artefacto.",
                null, details, externalActor, resolvedAt);
        UpdateTicketStatusEnvelope envelope = envelope(data);

        TicketResponse response = service.applyUpdate(ticketId, envelope);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(ticket.getStatusChangedAt()).isEqualTo(resolvedAt);
        assertThat(ticket.getResolutionConfirmationDueAt()).isEqualTo(resolvedAt.plus(Duration.ofHours(72)));
        verify(resolutionRepository).save(argThat(resolution ->
                resolution.getTicket() == ticket
                        && resolution.getType() == ResolutionType.ACTION_COMPLETED
                        && "La luminaria fue reparada.".equals(resolution.getPublicMessage())
                        && "Se reemplazó el artefacto.".equals(resolution.getInternalMessage())
                        && resolution.getResolvedByType() == com.reclamos.backend.entity.ActorType.EXTERNAL_USER
                        && "USR-M6-77".equals(resolution.getResolvedById())
                        && "M6".equals(resolution.getResolvedByModuleId())
                        && resolvedAt.equals(resolution.getResolvedAt())));
        verify(activityRepository).save(argThat(activity ->
                activity.getActionType() == ActivityType.RESOLVED
                        && activity.getPreviousStatus() == TicketStatus.IN_PROGRESS
                        && activity.getNewStatus() == TicketStatus.RESOLVED
                        && activity.getActorType() == com.reclamos.backend.entity.ActorType.EXTERNAL_USER
                        && "USR-M6-77".equals(activity.getActorId())
                        && envelope.eventId().equals(activity.getExternalEventId())
                        && resolvedAt.equals(activity.getOccurredAt())));
    }

    /**
     * Eventos §8.2: RESOLVED se acepta de forma incondicional tanto desde
     * ROUTED como desde IN_PROGRESS — no existe configuración por
     * RequestType que lo habilite o impida.
     */
    @Test
    void resolvedIsAcceptedDirectlyFromRoutedWithoutAnyRequestTypeConfiguration() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Listo.", null, null, details, externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
        verify(resolutionRepository).save(any());
        verify(activityRepository).save(argThat(activity ->
                activity.getPreviousStatus() == TicketStatus.ROUTED));
    }

    @Test
    void systemResolutionAllowsNullActorId() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACKNOWLEDGED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Procesado automáticamente.", null, null, details,
                new UpdateTicketStatusRequest.Actor("SYSTEM", null), Instant.parse("2026-09-08T10:00:00Z"));

        service.applyUpdate(ticketId, envelope(data));

        verify(resolutionRepository).save(argThat(resolution ->
                resolution.getResolvedByType() == com.reclamos.backend.entity.ActorType.SYSTEM
                        && resolution.getResolvedById() == null));
        verify(activityRepository).save(argThat(activity ->
                activity.getActorType() == com.reclamos.backend.entity.ActorType.SYSTEM
                        && activity.getActorId() == null));
    }

    @Test
    void localM2TicketCannotUseExternalUpdateFlow() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        ticket.setResponsibleAreaId("M2");
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Resuelto.", null, null, details,
                externalActor, Instant.now());
        UpdateTicketStatusEnvelope envelope = new UpdateTicketStatusEnvelope(
                "1.0", UUID.randomUUID(), "updateTicketStatus", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M2", "help-center-api"),
                "tickets/" + ticketId, data);

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope))
                .isInstanceOf(InvalidTicketRequestException.class);
        verify(resolutionRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
        verify(activityRepository, never()).save(any());
    }

    @Test
    void duplicateResolvedEventDoesNotRepeatResolutionActivityOrDeadline() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        Instant resolvedAt = Instant.parse("2026-09-08T11:00:00Z");
        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.REQUEST_FULFILLED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Solicitud completada.", null, null, details,
                externalActor, resolvedAt);
        UpdateTicketStatusEnvelope envelope = envelope(data);
        InboxEvent processed = new InboxEvent();
        processed.setEventId(envelope.eventId());
        processed.setStatus(InboxStatus.PROCESSED);
        when(inboxEventRepository.findById(envelope.eventId()))
                .thenReturn(Optional.empty(), Optional.of(processed));

        service.applyUpdate(ticketId, envelope);
        Instant originalDeadline = ticket.getResolutionConfirmationDueAt();
        service.applyUpdate(ticketId, envelope);

        assertThat(ticket.getResolutionConfirmationDueAt()).isEqualTo(originalDeadline);
        verify(resolutionRepository, times(1)).save(any());
        verify(activityRepository, times(1)).save(any());
        verify(ticketRepository, times(1)).save(any());
        verify(inboxEventRepository, times(1)).save(any());
    }

    @Test
    void resolvedOnIncompatibleStateDoesNotPersistAnyEffect() {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Resuelto.", null, null, details,
                externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(TicketStateConflictException.class);

        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.IN_REVIEW);
        verify(resolutionRepository, never()).save(any());
        verify(ticketRepository, never()).save(any());
        verify(activityRepository, never()).save(any());
        verify(inboxEventRepository, never()).save(any());
    }

    @Test
    void externallyResolvedTicketRemainsCompatibleWithExistingReopenFlow() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        UUID ownerId = UUID.randomUUID();
        ticket.setCitizenId(ownerId);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0, 1);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());
        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Trabajo terminado.", null, null, details,
                externalActor, Instant.parse("2026-09-08T10:00:00Z"));

        service.applyUpdate(ticketId, envelope(data));
        resolutionService.reopen(ticketId, new ReopenTicketRequest("El problema continúa"),
                new AuthenticatedIdentity("citizen-subject", ownerId, "CITIZEN", null, ModuleRole.CITIZEN));

        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getResolutionConfirmationDueAt()).isNull();
        assertThat(ticket.getReopenCount()).isEqualTo(1);
        verify(resolutionRepository).save(any());
        verify(activityRepository, times(2)).save(any());
    }

    @Test
    void rejectedMovesTicketToCancelled() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, null, new UpdateTicketStatusRequest.Cancellation("OUT_OF_SCOPE"));
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.REJECTED, "No corresponde a esta gestión.", null, null, details,
                externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void unknownActorTypeIsRejected() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest.Actor badActor = new UpdateTicketStatusRequest.Actor("ROBOT", "x");
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, badActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).save(any());
    }

    @Test
    void missingTicketThrowsNotFound() {
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- Validación de envelope, data.ticketId y deduplicación por eventId ----

    @Test
    void wrongEventTypeIsRejectedWithoutTouchingTheTicket() {
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope badEnvelope = new UpdateTicketStatusEnvelope(
                "1.0", UUID.randomUUID(), "ticketUpdated", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M6", "urban-services-api"),
                "tickets/" + ticketId, data);

        assertThatThrownBy(() -> service.applyUpdate(ticketId, badEnvelope))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).findByIdForUpdate(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void mismatchedSubjectIsRejectedWithoutTouchingTheTicket() {
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope badEnvelope = new UpdateTicketStatusEnvelope(
                "1.0", UUID.randomUUID(), "updateTicketStatus", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M6", "urban-services-api"),
                "tickets/" + UUID.randomUUID(), data);

        assertThatThrownBy(() -> service.applyUpdate(ticketId, badEnvelope))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).findByIdForUpdate(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void unsupportedSpecVersionIsRejectedWithoutTouchingTheTicket() {
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope badEnvelope = new UpdateTicketStatusEnvelope(
                "2.0", UUID.randomUUID(), "updateTicketStatus", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M6", "urban-services-api"),
                "tickets/" + ticketId, data);

        assertThatThrownBy(() -> service.applyUpdate(ticketId, badEnvelope))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).findByIdForUpdate(any());
    }

    /**
     * Eventos §8.1: data.ticketId es un campo propio de la variante de
     * negocio, distinto de envelope.subject (ya cubierto arriba por
     * mismatchedSubjectIsRejectedWithoutTouchingTheTicket).
     */
    @Test
    void ticketIdMismatchInDataIsRejectedWithoutTouchingTheTicket() {
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                UUID.randomUUID(), UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope envelope = envelope(data);

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).findByIdForUpdate(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void producerModuleMismatchWithResponsibleAreaIsRejected() {
        Ticket ticket = ticket(TicketStatus.ROUTED); // responsibleAreaId = "M6"
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope envelope = new UpdateTicketStatusEnvelope(
                "1.0", UUID.randomUUID(), "updateTicketStatus", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M9", "other-area-api"),
                "tickets/" + ticketId, data);

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).save(any());
        verify(activityRepository, never()).save(any());
    }

    @Test
    void duplicateEventIdReturnsIdempotentSuccessWithoutRepeatingEffects() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(0);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, "Comenzamos a trabajar en esto.", null,
                null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope envelope = envelope(data);

        InboxEvent alreadyProcessed = new InboxEvent();
        alreadyProcessed.setEventId(envelope.eventId());
        alreadyProcessed.setEventType(envelope.eventType());
        alreadyProcessed.setProducerModuleId(envelope.producer().moduleId());
        alreadyProcessed.setStatus(InboxStatus.PROCESSED);

        when(inboxEventRepository.findById(envelope.eventId()))
                .thenReturn(Optional.empty())          // primer envío: no visto
                .thenReturn(Optional.of(alreadyProcessed)); // reenvío: ya procesado

        TicketResponse first = service.applyUpdate(ticketId, envelope);
        TicketResponse second = service.applyUpdate(ticketId, envelope);

        assertThat(first.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(second.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        // El reenvío no debe repetir NINGÚN efecto de negocio: sólo una vez cada uno.
        verify(messageRepository, times(1)).save(any());
        verify(activityRepository, times(1)).save(any());
        verify(ticketRepository, times(1)).save(any());
        verify(inboxEventRepository, times(1)).save(any());
    }

    @Test
    void eventIdThatPreviouslyFailedIsRejectedAgainWithoutReprocessing() {
        UUID eventId = UUID.randomUUID();
        InboxEvent failed = new InboxEvent();
        failed.setEventId(eventId);
        failed.setStatus(InboxStatus.FAILED);
        failed.setError("subject no coincidía con el ticket de la URL");

        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, null, null, null, null, externalActor, Instant.now());
        UpdateTicketStatusEnvelope envelope = new UpdateTicketStatusEnvelope(
                "1.0", eventId, "updateTicketStatus", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M6", "urban-services-api"),
                "tickets/" + ticketId, data);

        when(inboxEventRepository.findById(eventId)).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope))
                .isInstanceOf(InvalidTicketRequestException.class);

        verify(ticketRepository, never()).findByIdForUpdate(any());
    }

    private UpdateTicketStatusEnvelope envelope(UpdateTicketStatusRequest data) {
        return new UpdateTicketStatusEnvelope(
                "1.0",
                UUID.randomUUID(),
                "updateTicketStatus",
                Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M6", "urban-services-api"),
                "tickets/" + ticketId,
                data
        );
    }

    private Ticket ticket(TicketStatus status) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Infraestructura");

        Subcategory subcategory = new Subcategory();
        subcategory.setId(10L);
        subcategory.setCategory(category);
        subcategory.setName("Vía pública");

        RequestType requestType = new RequestType();
        requestType.setId(5L);
        requestType.setCode("POTHOLE");
        requestType.setName("POTHOLE");
        requestType.setSubcategory(subcategory);
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M6");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setAffectedPopulationFactor(new BigDecimal("0.0500"));
        requestType.setActive(true);

        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setPublicId("OP-0000000002");
        ticket.setRequestType(requestType);
        ticket.setTicketType(TicketType.COMPLAINT);
        ticket.setResponsibleAreaId("M6");
        ticket.setSummary("Bache en la vereda");
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(Priority.MEDIUM);
        ticket.setEstimatedAffectedCount(0);
        ticket.setEscalated(false);
        ticket.setStatusChangedAt(Instant.now());
        return ticket;
    }
}
