package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.dto.response.InformationRequestResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InformationRequestService {
    private static final String MODULE_ID = "M2";
    // Estados desde los que un agente puede solicitar información adicional
    private static final EnumSet<TicketStatus> REQUESTABLE_STATUSES = EnumSet.of(
            TicketStatus.IN_REVIEW, TicketStatus.ROUTED, TicketStatus.IN_PROGRESS);

    private final TicketRepository ticketRepository;
    private final InformationRequestRepository informationRequestRepository;
    private final TicketCancellationRepository cancellationRepository;
    private final TicketActivityRepository activityRepository;
    private final InformationRequestDeadlineService deadlineService;

    @Transactional
    public InformationRequestResponse requestInformation(UUID ticketId, CreateInformationRequest request,
                                                         AuthenticatedIdentity identity) {
        requireAgent(identity); // Valida quien solicita información tenga permisos de agente
        Ticket ticket = lockedTicket(ticketId); // Se bloquea el ticket para evitar modificaciones concurrentes
        if (!REQUESTABLE_STATUSES.contains(ticket.getCurrentStatus())) { // No se permite solicitar información desde estados incompatibles
            throw new InformationRequestConflictException(
                    "El estado actual del ticket no permite solicitar información");
        }
        if (informationRequestRepository.existsByTicketIdAndStatus(ticketId, InformationRequestStatus.PENDING)) { // Un ticket solo puede tener una solicitud de información pendiente a la vez
            throw new InformationRequestConflictException("Ya existe una solicitud de información pendiente");
        }

        Instant requestedAt = deadlineService.now();
        TicketStatus resumeStatus = ticket.getCurrentStatus(); // Se guarda el estado actual para restaurarlo cuando el vecino responda
        InformationRequest informationRequest = new InformationRequest(); // Se crea la solicitud y se registra quién la realizó, el mensaje y el plazo
        informationRequest.setTicket(ticket);
        informationRequest.setRequestedByModuleId(MODULE_ID);
        informationRequest.setRequestedByActorType(ActorType.AGENT);
        informationRequest.setRequestedByActorId(identity.subjectId());
        informationRequest.setMessageForCitizen(request.getMessageForCitizen());
        informationRequest.setInternalMessage(request.getInternalMessage());
        informationRequest.setResumeStatus(resumeStatus);
        informationRequest.setStatus(InformationRequestStatus.PENDING);
        informationRequest.setRequestedAt(requestedAt);
        informationRequest.setDueAt(deadlineService.calculateDueAt(requestedAt)); // El deadline se calcula a partir de la fecha de solicitud y la duración configurada
        informationRequest = informationRequestRepository.save(informationRequest);

        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION); // Mientras se espera la respuesta, el ticket queda en PENDING_INFORMATION
        ticket.setStatusChangedAt(requestedAt);
        ticketRepository.save(ticket);
        saveActivity(ticket, ActivityType.INFORMATION_REQUIRED, resumeStatus,
                TicketStatus.PENDING_INFORMATION, ActorType.AGENT, identity.subjectId(),
                null, request.getMessageForCitizen(), requestedAt); // Se registra el cambio en el historial funcional del ticket
        return response(informationRequest);
    }

    @Transactional
    public InformationRequestResponse answerInformation(UUID ticketId, AnswerInformationRequest request,
                                                        AuthenticatedIdentity identity) {
        if (identity == null) throw new UnauthorizedTicketOperationException();
        Ticket ticket = lockedTicket(ticketId);
        if (ticket.isAnonymous() || ticket.getCitizenId() == null
                || !ticket.getCitizenId().equals(identity.citizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
        return answerPending(ticket, request.getResponseMessage(), identity.subjectId());
    }

    @Transactional
    public InformationRequestResponse answerAnonymousFromTracking(UUID ticketId, String responseMessage,
                                                                  String trackingActorId) {
        Ticket ticket = lockedTicket(ticketId);
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null) {
            throw new UnauthorizedTicketOperationException();
        }
        return answerPending(ticket, responseMessage, trackingActorId);
    }

    private InformationRequestResponse answerPending(Ticket ticket, String responseMessage, String actorId) {
        InformationRequest informationRequest = informationRequestRepository
                .findByTicketIdAndStatus(ticket.getId(), InformationRequestStatus.PENDING)
                .orElseThrow(() -> new InformationRequestConflictException(
                        "No existe una solicitud de información pendiente"));
        if (deadlineService.isExpired(informationRequest.getDueAt())) {
            throw new InformationRequestExpiredException();
        }
        Instant answeredAt = deadlineService.now();
        informationRequest.setResponseMessage(responseMessage);
        informationRequest.setAnsweredAt(answeredAt);
        informationRequest.setAnsweredByType(ActorType.CITIZEN);
        informationRequest.setAnsweredById(actorId);
        informationRequest.setStatus(InformationRequestStatus.ANSWERED);
        informationRequestRepository.save(informationRequest);

        ticket.setCurrentStatus(informationRequest.getResumeStatus());
        ticket.setStatusChangedAt(answeredAt);
        ticketRepository.save(ticket);
        saveActivity(ticket, ActivityType.INFORMATION_PROVIDED, TicketStatus.PENDING_INFORMATION,
                informationRequest.getResumeStatus(), ActorType.CITIZEN, actorId,
                null, responseMessage, answeredAt);
        return response(informationRequest);
    }

    @Scheduled(fixedDelayString = "${ticket.information-request.expiration-scan-delay:60000}") // Revisa periódicamente las solicitudes pendientes que se les venció el plazo
    @Transactional
    public void expireDueRequests() {
        Instant now = deadlineService.now();
        for (InformationRequest request : informationRequestRepository
                .findByStatusAndDueAtLessThanEqual(InformationRequestStatus.PENDING, now)) { // Solo se procesan solicitudes pendientens con fecha final menor o igual al momento actual
            if (request.getStatus() != InformationRequestStatus.PENDING) continue;
            request.setStatus(InformationRequestStatus.EXPIRED);
            informationRequestRepository.save(request);
            cancelForTimeout(request.getTicket(), now);
        }
    }

    private void cancelForTimeout(Ticket ticket, Instant now) {
        if (cancellationRepository.existsByTicketId(ticket.getId())) return;
        ticket.setCurrentStatus(TicketStatus.CANCELLED);
        ticket.setStatusChangedAt(now);
        ticketRepository.save(ticket);

        TicketCancellation cancellation = new TicketCancellation();
        cancellation.setTicket(ticket);
        cancellation.setReasonCode(CancellationReasonCode.INFO_TIMEOUT);
        cancellation.setPublicMessage("El ticket fue cancelado por falta de respuesta dentro del plazo");
        cancellation.setInternalMessage("Vencimiento automático de solicitud de información");
        cancellation.setCancelledByType(ActorType.ADMIN);
        cancellation.setCancelledById("system");
        cancellation.setCancelledByModuleId(MODULE_ID);
        cancellation.setCancelledAt(now);
        cancellationRepository.save(cancellation);
        saveActivity(ticket, ActivityType.CANCELLED, TicketStatus.PENDING_INFORMATION, TicketStatus.CANCELLED,
                ActorType.ADMIN, "system", CancellationReasonCode.INFO_TIMEOUT.name(),
                cancellation.getPublicMessage(), now);
    }

    private void saveActivity(Ticket ticket, ActivityType type, TicketStatus previous, TicketStatus next,
                              ActorType actorType, String actorId, String reason, String message, Instant at) {
        // Registra una nueva actividad en el historial funcional del ticket
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(type);
        activity.setPreviousStatus(previous);
        activity.setNewStatus(next);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(reason);
        activity.setMessage(message);
        activity.setOccurredAt(at);
        activityRepository.save(activity);
    }

    private Ticket lockedTicket(UUID ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
    }

    private void requireAgent(AuthenticatedIdentity identity) {
        if (identity == null || !identity.roles().contains(ModuleRole.AGENT)) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private InformationRequestResponse response(InformationRequest request) {
        return new InformationRequestResponse(request.getId(), request.getTicket().getId(), request.getStatus(),
                request.getMessageForCitizen(), request.getRequestedAt(), request.getDueAt(),
                request.getResumeStatus(), request.getResponseMessage(), request.getAnsweredAt());
    }
}