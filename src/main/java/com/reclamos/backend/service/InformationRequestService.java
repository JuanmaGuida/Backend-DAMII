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
import java.util.Objects;
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
    private final TicketActivityRepository activityRepository;
    private final InformationRequestDeadlineService deadlineService;
    private final InformationRequestExpirationService expirationService;

    @Transactional
    public InformationRequestResponse requestInformation(UUID ticketId, CreateInformationRequest request,
                                                         AuthenticatedIdentity identity) {
        requireInformationRequester(identity);
        Ticket ticket = lockedTicket(ticketId); // Se bloquea el ticket para evitar modificaciones concurrentes
        if (Objects.equals(identity.citizenId(), ticket.getCitizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
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
        ActorType requesterType = identity.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT;
        String actorId = identity.citizenId().toString();
        informationRequest.setRequestedByActorType(requesterType);
        informationRequest.setRequestedByActorId(actorId);
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
                TicketStatus.PENDING_INFORMATION, requesterType, actorId,
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
        return answerPending(ticket, request.getResponseMessage(), identity.citizenId().toString());
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
                .findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING)
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
    public void expireDueRequests() {
        Instant now = deadlineService.now();
        informationRequestRepository.findExpirationCandidates(InformationRequestStatus.PENDING, now)
                .forEach(candidate -> expirationService.expireIfDue(
                        candidate.getRequestId(), candidate.getTicketId(), now));
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

    private void requireInformationRequester(AuthenticatedIdentity identity) {
        if (identity == null
                || (identity.role() != ModuleRole.AGENT && identity.role() != ModuleRole.ADMIN)) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private InformationRequestResponse response(InformationRequest request) {
        return new InformationRequestResponse(request.getId(), request.getTicket().getId(), request.getStatus(),
                request.getMessageForCitizen(), request.getRequestedAt(), request.getDueAt(),
                request.getResumeStatus(), request.getResponseMessage(), request.getAnsweredAt());
    }
}
