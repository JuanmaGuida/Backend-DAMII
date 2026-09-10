package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.UpdateTicketStatusEnvelope;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.InboxEvent;
import com.reclamos.backend.entity.InboxStatus;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.entity.UpdateTicketStatusType;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.InboxEventRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Post-QA: estos tests ya no llaman a applyUpdate con el payload plano —
 * ahora reciben el {@link UpdateTicketStatusEnvelope} completo, y se agregan
 * casos específicos para lo que QA encontró roto: envelope inválido aceptado
 * sin cambiar nada, el mismo eventId reenviado duplicando efectos,
 * data.ticketId sin validar, RESOLVED desde ROUTED rechazado sin motivo, y
 * trazabilidad (externalEventId/updateOccurredAt) sin persistirse en
 * TicketActivity.
 * <p>
 * Revisión post-actualización de documentación: el actor de estos eventos
 * llega desde otro módulo (M6) vía integración, así que su
 * updatedBy.type correcto es EXTERNAL_USER (Eventos V1.69 §5.2, ejemplos de
 * §8.2/§8.3) — no AREA_USER, que además nunca fue un valor válido de
 * ActorType (ver su javadoc).
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

    private TicketStatusUpdateService service;

    private final UUID ticketId = UUID.randomUUID();
    private final UpdateTicketStatusRequest.Actor externalActor =
            new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", "USR-M6-77");

    @BeforeEach
    void setUp() {
        service = new TicketStatusUpdateService(ticketRepository, activityRepository, locationRepository,
                messageRepository, inboxEventRepository);
        // Default para los tests que no ejercitan dedupe en sí: "eventId nunca visto".
        // Los tests de dedupe pisan este stub explícitamente.
        lenient().when(inboxEventRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void startedMovesRoutedTicketToInProgressAndRecordsTraceability() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        Instant realOccurredAt = Instant.now().minusSeconds(120);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.STARTED, "Comenzamos a trabajar en esto.", null,
                null, null, externalActor, realOccurredAt);
        UpdateTicketStatusEnvelope envelope = envelope(data);

        TicketResponse response = service.applyUpdate(ticketId, envelope);

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(messageRepository).save(any());

        // QA (BE - Implementar transiciones a partir del consumo de eventos):
        // externalEventId y occurredAt no se persistían en TicketActivity.
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
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(1L);
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
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
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
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
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
                null, null, new UpdateTicketStatusRequest.Resolution("ACTION_COMPLETED"), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, null, "Se cambió la lámpara.", null, details,
                externalActor, Instant.now());

        assertThatThrownBy(() -> service.applyUpdate(ticketId, envelope(data)))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void resolvedMovesTicketToResolved() {
        Ticket ticket = ticket(TicketStatus.IN_PROGRESS);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(2L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution("ACTION_COMPLETED"), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "La luminaria fue reparada.", "Se reemplazó el artefacto.",
                null, details, externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    /**
     * Revisión post-actualización de documentación: la versión anterior de
     * este test ("...WhenRequestTypeDoesNotAllowDirectResolution") esperaba
     * que RESOLVED se RECHAZARA desde ROUTED por default, modelando una
     * lectura de Eventos v1.6 que resultó ambigua. Eventos V1.69 §8.2 aclara
     * que RESOLVED se acepta de forma incondicional tanto desde ROUTED como
     * desde IN_PROGRESS ("M2 no mantiene una configuración por RequestType
     * para habilitar o impedir la resolución directa"), así que el
     * comportamiento correcto es el opuesto al que este test verificaba
     * antes.
     */
    @Test
    void resolvedIsAcceptedDirectlyFromRoutedWithoutAnyRequestTypeConfiguration() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
        when(locationRepository.findByTicket_Id(ticketId)).thenReturn(Optional.empty());

        UpdateTicketStatusRequest.Details details = new UpdateTicketStatusRequest.Details(
                null, null, new UpdateTicketStatusRequest.Resolution("ACTION_COMPLETED"), null);
        UpdateTicketStatusRequest data = new UpdateTicketStatusRequest(
                ticketId, UpdateTicketStatusType.RESOLVED, "Listo.", null, null, details, externalActor, Instant.now());

        TicketResponse response = service.applyUpdate(ticketId, envelope(data));

        assertThat(response.getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    void rejectedMovesTicketToCancelled() {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
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

    // ---- Regresión QA: validación de envelope, data.ticketId y deduplicación por eventId ----

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
     * QA (BE - Implementar transiciones a partir del consumo de eventos):
     * Eventos v1.6 §8.1 exige data.ticketId como campo propio de la variante
     * de negocio, distinto de envelope.subject (ya cubierto arriba por
     * mismatchedSubjectIsRejectedWithoutTouchingTheTicket). Antes no se
     * validaba en absoluto.
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
        when(activityRepository.countByTicket_Id(ticketId)).thenReturn(0L);
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
