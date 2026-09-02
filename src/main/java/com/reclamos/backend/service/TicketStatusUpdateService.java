package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
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
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketLocationRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * DDA2-62 (BE - Implementar transiciones a partir del consumo de eventos):
 * aplica la MISMA lógica de transición canónica que va a usar la
 * integración real de updateTicketStatus (Eventos v1.6 §8), a partir de un
 * payload ya parseado. Hoy la única forma de llegar a este service es el
 * simulador interno (DDA2-61 / {@code TicketSimulationController}); cuando
 * exista un consumidor real de bus de eventos, va a llamar a este mismo
 * método sin tener que tocar la lógica de negocio.
 * <p>
 * ALCANCE REDUCIDO A PROPÓSITO respecto del contrato completo (documentado
 * updateType por updateType más abajo): no existen todavía las entidades
 * InformationRequest, TicketResolution ni TicketCancellation (Sprint 4/5),
 * así que esos hechos se registran con los campos genéricos que ya tiene
 * TicketActivity (reasonCode, message) en lugar de un modelo dedicado. Esto
 * es explícitamente temporal y hay que revisarlo cuando esas entidades se
 * construyan.
 * <p>
 * Tampoco se implementa la republicación de ticketUpdated después de
 * consumir un updateTicketStatus (Eventos v1.6 §10) ni InboxEvent/dedupe
 * por eventId — el AC de la Story 3.4 pide reproducir la transición, no el
 * eco de vuelta al bus. Ver aviso en el chat.
 */
@Service
@RequiredArgsConstructor
public class TicketStatusUpdateService {

    private static final Set<String> KNOWN_ACTOR_TYPES = Set.of("CITIZEN", "AGENT", "AREA_USER", "SYSTEM");

    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketLocationRepository locationRepository;
    private final TicketMessageRepository messageRepository;

    @Transactional
    public TicketResponse applyUpdate(UUID ticketId, UpdateTicketStatusRequest request) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));

        ActorType actorType = mapActorType(request.updatedBy().type());
        String reasonCode = null;
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
                // Simplificación: el contrato admite RESOLVED también desde ROUTED cuando el
                // RequestType permite resolución directa; esa excepción no está modelada
                // todavía (no hay flag en RequestType para "resolución directa"), así que
                // por ahora sólo se acepta desde IN_PROGRESS.
                requireCurrentStatus(ticket, TicketStatus.IN_PROGRESS, "RESOLVED sólo es válido con el ticket en IN_PROGRESS");
                UpdateTicketStatusRequest.Resolution resolution = request.details() == null
                        ? null : request.details().resolution();
                if (resolution == null || isBlank(resolution.type())) {
                    throw new InvalidTicketRequestException("RESOLVED requiere details.resolution.type");
                }
                if (isBlank(request.publicMessage())) {
                    throw new InvalidTicketRequestException("RESOLVED requiere publicMessage");
                }
                newStatus = TicketStatus.RESOLVED;
                activityType = ActivityType.RESOLVED;
                reasonCode = resolution.type();
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

        ticket.setCurrentStatus(newStatus);
        ticket.setStatusChangedAt(Instant.now());
        ticketRepository.save(ticket);

        if (!isBlank(request.publicMessage())) {
            messageRepository.save(buildMessage(ticket, MessageVisibility.PUBLIC, request.publicMessage(),
                    actorType, request.updatedBy().id(), request.producerModuleId()));
        }
        if (!isBlank(request.internalMessage())) {
            messageRepository.save(buildMessage(ticket, MessageVisibility.INTERNAL, request.internalMessage(),
                    actorType, request.updatedBy().id(), request.producerModuleId()));
        }

        recordActivity(ticket, activityType, previousStatus, newStatus, actorType, request.updatedBy().id(),
                request.producerModuleId(), reasonCode,
                !isBlank(request.internalMessage()) ? request.internalMessage() : request.publicMessage());

        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toResponse(ticket, location);
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

    private ActorType mapActorType(String contractType) {
        if (contractType == null || !KNOWN_ACTOR_TYPES.contains(contractType)) {
            throw new InvalidTicketRequestException(
                    "updatedBy.type inválido: debe ser CITIZEN, AGENT, AREA_USER o SYSTEM");
        }
        // El contrato de eventos v1.6 §5.2 todavía usa los nombres AREA_USER/SYSTEM
        // (está pendiente actualizarlo tras el rename de ActorType a
        // AREA_RESPONSIBLE/ADMIN). Se mapea acá hasta que ese documento se actualice.
        return switch (contractType) {
            case "CITIZEN" -> ActorType.CITIZEN;
            case "AGENT" -> ActorType.AGENT;
            case "AREA_USER" -> ActorType.AREA_RESPONSIBLE;
            case "SYSTEM" -> ActorType.ADMIN;
            default -> throw new InvalidTicketRequestException("updatedBy.type inválido: " + contractType);
        };
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
                                 String sourceModuleId, String reasonCode, String message) {
        long nextSequence = activityRepository.countByTicket_Id(ticket.getId()) + 1;

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) nextSequence);
        activity.setActionType(actionType);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(sourceModuleId);
        activity.setReasonCode(reasonCode);
        activity.setMessage(message);
        activity.setOccurredAt(Instant.now());
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
        response.setAssignedAgentId(ticket.getAssignedAgentId());
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
