package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.UpdateTicketStatusEnvelope;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.InboxEvent;
import com.reclamos.backend.entity.InboxStatus;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketMessage;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.UpdateTicketStatusType;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.InboxEventRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 3.4 / DDA2-62: aplica la MISMA lógica de transición canónica que va
 * a usar la integración real de updateTicketStatus (Eventos §8), a partir
 * de un envelope ya deserializado. Hoy la única forma de llegar a este
 * service es el simulador interno ({@code TicketSimulationController});
 * cuando exista un consumidor real de bus de eventos, va a llamar a este
 * mismo método sin tener que tocar la lógica de negocio.
 * <p>
 * {@code applyUpdate} valida el {@link UpdateTicketStatusEnvelope} completo
 * ANTES de tocar el Ticket (§23.3 paso 1), deduplica por eventId contra
 * {@link InboxEventRepository} (§19.1) y valida producer.moduleId contra
 * responsibleAreaId (§23.3 paso 2) antes de aplicar cualquier transición.
 * <p>
 * La resolución delega sus efectos de dominio en {@link TicketResolutionService}
 * para que el simulador y un futuro consumer real compartan exactamente el
 * mismo caso de uso. La republicación de ticketUpdated sigue fuera de alcance.
 * <p>
 * NO IMPLEMENTADO A PROPÓSITO: si el envelope es válido pero el payload de
 * negocio no lo es para ese updateType (ej. falta details.returnInfo), la
 * excepción revierte toda la transacción — incluida cualquier fila de
 * InboxEvent que se hubiera intentado insertar en el medio — así que no
 * queda un registro FAILED auditable de ese intento. Un reintento con el
 * mismo eventId ya corregido simplemente se vuelve a procesar de cero, lo
 * cual es el comportamiento correcto para un productor que corrige y
 * reenvía; lo que no existe es un historial de los intentos rechazados con
 * ese eventId. Habría que persistir el InboxEvent(FAILED) en una
 * transacción separada (REQUIRES_NEW) para lograr eso.
 */
@Service
@RequiredArgsConstructor
public class TicketStatusUpdateService {

    private static final String SUPPORTED_SPEC_VERSION = "1.0";
    private static final String SUPPORTED_EVENT_TYPE = "updateTicketStatus";
    private static final String LOCAL_MODULE_ID = "M2";

    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketLocationRepository locationRepository;
    private final TicketMessageRepository messageRepository;
    private final InboxEventRepository inboxEventRepository;
    private final TicketResolutionService ticketResolutionService;

    @Transactional
    public TicketResponse applyUpdate(UUID ticketId, UpdateTicketStatusEnvelope envelope) {
        validateEnvelope(ticketId, envelope);

        Optional<InboxEvent> existing = inboxEventRepository.findById(envelope.eventId());
        if (existing.isPresent()) {
            return handleDuplicateEvent(ticketId, existing.get());
        }

        UpdateTicketStatusRequest request = envelope.data();
        // Eventos §8.1 exige data.ticketId como campo propio, no alcanza con
        // envelope.subject (que ya se valida en validateEnvelope).
        if (!ticketId.equals(request.ticketId())) {
            throw new InvalidTicketRequestException(
                    "data.ticketId ('" + request.ticketId() + "') no coincide con el ticket de la URL ('"
                            + ticketId + "')");
        }
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));

        if (!envelope.producer().moduleId().equalsIgnoreCase(ticket.getResponsibleAreaId())) {
            throw new InvalidTicketRequestException(
                    "producer.moduleId ('" + envelope.producer().moduleId()
                            + "') no coincide con el área responsable del ticket ('"
                            + ticket.getResponsibleAreaId() + "')");
        }
        if (LOCAL_MODULE_ID.equalsIgnoreCase(ticket.getResponsibleAreaId())) {
            throw new InvalidTicketRequestException(
                    "Los tickets gestionados por M2 deben utilizar el flujo de resolución manual");
        }

        ActorType actorType = mapActorType(request.updatedBy().type());
        requireExternalActorType(actorType);
        String reasonCode = null;
        ResolutionType resolutionType = null;
        ActivityType activityType;
        TicketStatus previousStatus = ticket.getCurrentStatus();
        TicketStatus newStatus;

        switch (request.updateType()) {
            case STARTED -> {
                requireCurrentStatus(ticket, TicketStatus.ROUTED, "STARTED sólo es válido con el ticket en ROUTED");
                newStatus = TicketStatus.IN_PROGRESS;
                activityType = ActivityType.STATE_CHANGED;
            }
            case PROGRESS -> {
                requireCurrentStatus(ticket, TicketStatus.IN_PROGRESS, "PROGRESS sólo es válido con el ticket en IN_PROGRESS");
                if (request.progress() == null && isBlank(request.publicMessage()) && isBlank(request.internalMessage())) {
                    throw new InvalidTicketRequestException(
                            "PROGRESS debe aportar al menos progress, publicMessage o internalMessage");
                }
                newStatus = TicketStatus.IN_PROGRESS;
                activityType = ActivityType.PROGRESS_REPORTED;
                if (request.progress() != null) {
                    ticket.setCurrentProgress(request.progress().shortValue());
                }
            }
            case INFORMATION_REQUIRED -> {
                requireCurrentStatus(ticket, "INFORMATION_REQUIRED sólo es válido con el ticket en ROUTED o IN_PROGRESS",
                        TicketStatus.ROUTED, TicketStatus.IN_PROGRESS);
                UpdateTicketStatusRequest.InformationRequest info = request.details() == null
                        ? null : request.details().informationRequest();
                if (info == null || isBlank(info.messageForCitizen())) {
                    throw new InvalidTicketRequestException(
                            "INFORMATION_REQUIRED requiere details.informationRequest.messageForCitizen");
                }
                newStatus = TicketStatus.PENDING_INFORMATION;
                activityType = ActivityType.INFORMATION_REQUIRED;
            }
            case RETURNED -> {
                requireCurrentStatus(ticket, "RETURNED sólo es válido con el ticket en ROUTED o IN_PROGRESS",
                        TicketStatus.ROUTED, TicketStatus.IN_PROGRESS);
                UpdateTicketStatusRequest.ReturnInfo returnInfo = request.details() == null
                        ? null : request.details().returnInfo();
                if (returnInfo == null || isBlank(returnInfo.reasonCode())) {
                    throw new InvalidTicketRequestException("RETURNED requiere details.returnInfo.reasonCode");
                }
                if (isBlank(request.publicMessage()) && isBlank(request.internalMessage())) {
                    throw new InvalidTicketRequestException(
                            "RETURNED requiere publicMessage o internalMessage explicando la devolución");
                }
                newStatus = TicketStatus.IN_REVIEW;
                activityType = ActivityType.RETURNED_BY_AREA;
                reasonCode = returnInfo.reasonCode();
            }
            case RESOLVED -> {
                // Eventos §8.2: RESOLVED se acepta de forma incondicional tanto desde
                // ROUTED como desde IN_PROGRESS. M2 no mantiene una configuración por
                // RequestType para habilitar o impedir la resolución directa (ver
                // V9__drop_request_type_direct_resolution.sql).
                requireCurrentStatus(ticket, "RESOLVED sólo es válido con el ticket en ROUTED o IN_PROGRESS",
                        TicketStatus.ROUTED, TicketStatus.IN_PROGRESS);
                UpdateTicketStatusRequest.Resolution resolution = request.details() == null
                        ? null : request.details().resolution();
                if (resolution == null || resolution.type() == null) {
                    throw new InvalidTicketRequestException("RESOLVED requiere details.resolution.type");
                }
                if (isBlank(request.publicMessage())) {
                    throw new InvalidTicketRequestException("RESOLVED requiere publicMessage");
                }
                newStatus = TicketStatus.RESOLVED;
                activityType = ActivityType.RESOLVED;
                resolutionType = resolution.type();
                reasonCode = resolution.type().name();
            }
            case REJECTED -> {
                requireCurrentStatus(ticket, "REJECTED sólo es válido con el ticket en ROUTED o IN_PROGRESS",
                        TicketStatus.ROUTED, TicketStatus.IN_PROGRESS);
                UpdateTicketStatusRequest.Cancellation cancellation = request.details() == null
                        ? null : request.details().cancellation();
                if (cancellation == null || isBlank(cancellation.reasonCode())) {
                    throw new InvalidTicketRequestException("REJECTED requiere details.cancellation.reasonCode");
                }
                if (isBlank(request.publicMessage()) && isBlank(request.internalMessage())) {
                    throw new InvalidTicketRequestException(
                            "REJECTED requiere publicMessage o internalMessage explicando el rechazo");
                }
                newStatus = TicketStatus.CANCELLED;
                activityType = ActivityType.CANCELLED;
                reasonCode = cancellation.reasonCode();
            }
            default -> throw new InvalidTicketRequestException("updateType no soportado: " + request.updateType());
        }

        if (request.updateType() == UpdateTicketStatusType.RESOLVED) {
            ticketResolutionService.applyValidatedResolution(ticket,
                    new TicketResolutionService.ResolutionApplication(
                            resolutionType, request.publicMessage(), request.internalMessage(),
                            actorType, request.updatedBy().id(), envelope.producer().moduleId(),
                            request.updateOccurredAt(), envelope.eventId()));
        } else {
            ticket.setCurrentStatus(newStatus);
            ticket.setStatusChangedAt(Instant.now());
            ticketRepository.save(ticket);
        }

        String sourceModuleId = envelope.producer().moduleId();
        if (!isBlank(request.publicMessage())) {
            messageRepository.save(buildMessage(ticket, MessageVisibility.PUBLIC, request.publicMessage(),
                    actorType, request.updatedBy().id(), sourceModuleId));
        }
        if (!isBlank(request.internalMessage())) {
            messageRepository.save(buildMessage(ticket, MessageVisibility.INTERNAL, request.internalMessage(),
                    actorType, request.updatedBy().id(), sourceModuleId));
        }

        if (request.updateType() != UpdateTicketStatusType.RESOLVED) {
            recordActivity(ticket, activityType, previousStatus, newStatus, actorType, request.updatedBy().id(),
                    sourceModuleId, reasonCode,
                    !isBlank(request.internalMessage()) ? request.internalMessage() : request.publicMessage(),
                    envelope.eventId(), request.updateOccurredAt());
        }

        // InboxEvent se inserta en la MISMA transacción que el resto: si esto
        // violara la unicidad de eventId (dos requests concurrentes con el
        // mismo eventId ganándole ambos al chequeo de arriba), todo el
        // commit falla junto y ninguna de las dos aplica efectos duplicados.
        inboxEventRepository.save(toInboxEvent(envelope));

        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toResponse(ticket, location);
    }

    /**
     * Eventos §23.3 paso 1: valida el envelope común ANTES de tocar el
     * Ticket. specVersion/eventType/subject inválidos son rechazo puro de
     * request (400), sin ningún efecto de negocio.
     */
    private void validateEnvelope(UUID ticketId, UpdateTicketStatusEnvelope envelope) {
        if (!SUPPORTED_SPEC_VERSION.equals(envelope.specVersion())) {
            throw new InvalidTicketRequestException(
                    "specVersion no soportado: " + envelope.specVersion() + " (se espera " + SUPPORTED_SPEC_VERSION + ")");
        }
        if (!SUPPORTED_EVENT_TYPE.equals(envelope.eventType())) {
            throw new InvalidTicketRequestException(
                    "eventType inválido: se esperaba '" + SUPPORTED_EVENT_TYPE + "'");
        }
        String expectedSubject = "tickets/" + ticketId;
        if (!expectedSubject.equals(envelope.subject())) {
            throw new InvalidTicketRequestException(
                    "subject ('" + envelope.subject() + "') no coincide con el ticket de la URL ('"
                            + expectedSubject + "')");
        }
    }

    /**
     * Eventos v1.6 §12 "Errores y reintentos": eventId ya procesado se
     * confirma como éxito idempotente sin repetir efectos. Un eventId que ya
     * falló antes se rechaza de nuevo con el mismo motivo, en vez de
     * reprocesarlo silenciosamente — un reintento legítimo de un productor
     * que corrigió el payload debería llegar con un eventId nuevo.
     */
    private TicketResponse handleDuplicateEvent(UUID ticketId, InboxEvent existing) {
        if (existing.getStatus() == InboxStatus.FAILED) {
            throw new InvalidTicketRequestException(
                    "El evento " + existing.getEventId() + " ya fue rechazado anteriormente: " + existing.getError());
        }
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toResponse(ticket, location);
    }

    private InboxEvent toInboxEvent(UpdateTicketStatusEnvelope envelope) {
        InboxEvent event = new InboxEvent();
        event.setEventId(envelope.eventId());
        event.setEventType(envelope.eventType());
        event.setProducerModuleId(envelope.producer().moduleId());
        Instant now = Instant.now();
        event.setReceivedAt(now);
        event.setProcessedAt(now);
        event.setStatus(InboxStatus.PROCESSED);
        return event;
    }

    private void requireCurrentStatus(Ticket ticket, TicketStatus expected, String message) {
        requireCurrentStatus(ticket, message, expected);
    }

    private void requireCurrentStatus(Ticket ticket, String message, TicketStatus... expected) {
        for (TicketStatus status : expected) {
            if (ticket.getCurrentStatus() == status) {
                return;
            }
        }
        throw new TicketStateConflictException(message + " (estado actual: " + ticket.getCurrentStatus() + ")");
    }

    /**
     * Valida directamente contra ActorType.valueOf() para que la única
     * fuente de verdad sea el enum (ver su javadoc para el significado de
     * cada valor, incluido EXTERNAL_USER para actores que llegan desde otro
     * módulo vía integración).
     */
    private ActorType mapActorType(String contractType) {
        try {
            return ActorType.valueOf(contractType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidTicketRequestException(
                    "updatedBy.type inválido: debe ser uno de " + java.util.Arrays.toString(ActorType.values()));
        }
    }

    private void requireExternalActorType(ActorType actorType) {
        if (actorType != ActorType.EXTERNAL_USER && actorType != ActorType.SYSTEM) {
            throw new InvalidTicketRequestException(
                    "updatedBy.type debe ser EXTERNAL_USER o SYSTEM para hechos de un módulo externo");
        }
    }

    private TicketMessage buildMessage(Ticket ticket, MessageVisibility visibility, String text,
                                        ActorType actorType, String actorId, String sourceModuleId) {
        TicketMessage message = new TicketMessage();
        message.setTicket(ticket);
        message.setAuthorType(actorType);
        message.setAuthorId(actorId);
        message.setSourceModuleId(sourceModuleId);
        message.setVisibility(visibility);
        message.setText(text);
        return message;
    }

    private void recordActivity(Ticket ticket, ActivityType actionType, TicketStatus previousStatus,
                                 TicketStatus newStatus, ActorType actorType, String actorId,
                                 String sourceModuleId, String reasonCode, String message,
                                 UUID externalEventId, Instant updateOccurredAt) {
        long nextSequence = activityRepository.countByTicketId(ticket.getId()) + 1;

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) nextSequence);
        activity.setActionType(actionType);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(sourceModuleId);
        // occurredAt registra data.updateOccurredAt (cuándo ocurrió el hecho en
        // el productor), no el momento en que M2 lo procesó.
        activity.setExternalEventId(externalEventId);
        activity.setReasonCode(reasonCode);
        activity.setMessage(message);
        activity.setOccurredAt(updateOccurredAt);
        activityRepository.save(activity);
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location) {
        RequestType requestType = ticket.getRequestType();
        Subcategory subcategory = requestType.getSubcategory();
        Category category = subcategory.getCategory();

        TicketResponse response = new TicketResponse();
        response.setId(ticket.getId());
        response.setPublicId(ticket.getPublicId());
        response.setRequestTypeCode(requestType.getCode());
        response.setRequestTypeName(requestType.getName());
        response.setCategoryName(category.getName());
        response.setSubcategoryName(subcategory.getName());
        response.setTicketType(ticket.getTicketType());
        response.setSummary(ticket.getSummary());
        response.setCurrentStatus(ticket.getCurrentStatus());
        response.setCurrentPriority(ticket.getCurrentPriority());
        response.setResponsibleAreaId(ticket.getResponsibleAreaId());
        response.setAssignedAgentId(ticket.getAssignedAgent() != null
                ? String.valueOf(ticket.getAssignedAgent().getId()) : null);
        response.setAnonymous(ticket.isAnonymous());
        response.setEstimatedAffectedCount(ticket.getEstimatedAffectedCount());
        response.setEscalated(ticket.isEscalated());
        if (location != null && location.getNeighborhood() != null) {
            response.setNeighborhoodId(location.getNeighborhood().getId());
            response.setNeighborhoodName(location.getNeighborhood().getName());
        }
        response.setClassificationFinalizedAt(ticket.getClassificationFinalizedAt());
        response.setStatusChangedAt(ticket.getStatusChangedAt());
        response.setCreatedAt(ticket.getCreatedAt());
        response.setUpdatedAt(ticket.getUpdatedAt());
        return response;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
