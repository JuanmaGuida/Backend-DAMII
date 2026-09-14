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
    private final TicketSlaService ticketSlaService;
    private final TicketOutboxService ticketOutboxService;

    @Transactional
    public InformationRequestResponse requestInformation(UUID ticketId, CreateInformationRequest request,
                                                         AuthenticatedIdentity identity) {
        requireInformationRequester(identity);
        Ticket ticket = lockedTicket(ticketId); // Se bloquea el ticket para evitar modificaciones concurrentes
        if (Objects.equals(identity.citizenId(), ticket.getCitizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
        Instant requestedAt = deadlineService.now();
        InformationRequest informationRequest = createPending(ticket,
                new InformationRequestApplication(
                        MODULE_ID,
                        identity.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT,
                        identity.citizenId().toString(), request.getMessageForCitizen(), request.getInternalMessage(),
                        requestedAt, deadlineService.calculateDueAt(requestedAt), null));
        return response(informationRequest);
    }

    InformationRequest requestInformationFromExternal(Ticket lockedTicket, String sourceModuleId,
                                                        ActorType actorType, String actorId,
                                                        String messageForCitizen, String internalMessage,
                                                        Instant requestedAt, Instant requiredBy,
                                                        UUID externalEventId) {
        Instant dueAt = requiredBy != null ? requiredBy : deadlineService.calculateDueAt(requestedAt);
        if (!dueAt.isAfter(requestedAt)) {
            throw new InvalidTicketRequestException("details.informationRequest.requiredBy debe ser posterior a updateOccurredAt");
        }
        return createPending(lockedTicket, new InformationRequestApplication(
                sourceModuleId, actorType, actorId, messageForCitizen, internalMessage,
                requestedAt, dueAt, externalEventId));
    }

    private InformationRequest createPending(Ticket ticket, InformationRequestApplication application) {
        if (!REQUESTABLE_STATUSES.contains(ticket.getCurrentStatus())) {
            throw new InformationRequestConflictException(
                    "El estado actual del ticket no permite solicitar información");
        }
        if (informationRequestRepository.existsByTicketIdAndStatus(
                ticket.getId(), InformationRequestStatus.PENDING)) {
            throw new InformationRequestConflictException("Ya existe una solicitud de información pendiente");
        }

        TicketStatus resumeStatus = ticket.getCurrentStatus();
        InformationRequest informationRequest = new InformationRequest(); // Se crea la solicitud y se registra quién la realizó, el mensaje y el plazo
        informationRequest.setTicket(ticket);
        informationRequest.setRequestedByModuleId(application.sourceModuleId());
        informationRequest.setRequestedByActorType(application.actorType());
        informationRequest.setRequestedByActorId(application.actorId());
        informationRequest.setMessageForCitizen(application.messageForCitizen());
        informationRequest.setInternalMessage(application.internalMessage());
        informationRequest.setResumeStatus(resumeStatus);
        informationRequest.setStatus(InformationRequestStatus.PENDING);
        informationRequest.setRequestedAt(application.requestedAt());
        informationRequest.setDueAt(application.dueAt());
        informationRequest = informationRequestRepository.save(informationRequest);

        ticketSlaService.pauseActiveResolutionCycle(ticket, application.requestedAt());
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION); // Mientras se espera la respuesta, el ticket queda en PENDING_INFORMATION
        ticket.setStatusChangedAt(application.requestedAt());
        ticketRepository.save(ticket);
        saveActivity(ticket, ActivityType.INFORMATION_REQUIRED, resumeStatus,
                TicketStatus.PENDING_INFORMATION, application.actorType(), application.actorId(),
                application.sourceModuleId(), null, application.messageForCitizen(), application.requestedAt(),
                application.externalEventId());
        return informationRequest;
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
        if (ticket.getCurrentStatus() != TicketStatus.PENDING_INFORMATION) {
            throw new InformationRequestConflictException(
                    "El ticket ya no admite respuestas a solicitudes de información");
        }
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

        ticketSlaService.resumeActiveResolutionCycle(ticket, answeredAt);
        ticket.setCurrentStatus(informationRequest.getResumeStatus());
        ticket.setStatusChangedAt(answeredAt);
        ticketRepository.save(ticket);
        saveActivity(ticket, ActivityType.INFORMATION_PROVIDED, TicketStatus.PENDING_INFORMATION,
                informationRequest.getResumeStatus(), ActorType.CITIZEN, actorId,
                MODULE_ID, null, responseMessage, answeredAt, null);
        ticketOutboxService.informationProvided(ticket, responseMessage,
                !MODULE_ID.equalsIgnoreCase(informationRequest.getRequestedByModuleId()), answeredAt);
        return response(informationRequest);
    }

    /**
     * Terminaliza la solicitud que dejó de aplicar porque su Ticket fue
     * cancelado. El llamador debe mantener el lock pesimista del Ticket para
     * preservar el orden Ticket -&gt; InformationRequest usado por answer/expiry.
     */
    void cancelPendingBecauseTicketTerminated(Ticket lockedTicket) {
        informationRequestRepository
                .findByTicketIdAndStatusForUpdate(lockedTicket.getId(), InformationRequestStatus.PENDING)
                .ifPresent(request -> {
                    request.setStatus(InformationRequestStatus.CANCELLED);
                    informationRequestRepository.save(request);
                });
    }

    @Scheduled(fixedDelayString = "${ticket.information-request.expiration-scan-delay:60000}") // Revisa periódicamente las solicitudes pendientes que se les venció el plazo
    public void expireDueRequests() {
        Instant now = deadlineService.now();
        informationRequestRepository.findExpirationCandidates(InformationRequestStatus.PENDING, now)
                .forEach(candidate -> expirationService.expireIfDue(
                        candidate.getRequestId(), candidate.getTicketId(), now));
    }

    private void saveActivity(Ticket ticket, ActivityType type, TicketStatus previous, TicketStatus next,
                              ActorType actorType, String actorId, String sourceModuleId, String reason,
                              String message, Instant at, UUID externalEventId) {
        // Registra una nueva actividad en el historial funcional del ticket
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(type);
        activity.setPreviousStatus(previous);
        activity.setNewStatus(next);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(sourceModuleId);
        activity.setExternalEventId(externalEventId);
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

    private record InformationRequestApplication(
            String sourceModuleId,
            ActorType actorType,
            String actorId,
            String messageForCitizen,
            String internalMessage,
            Instant requestedAt,
            Instant dueAt,
            UUID externalEventId
    ) {
        private InformationRequestApplication {
            Objects.requireNonNull(sourceModuleId, "sourceModuleId es obligatorio");
            Objects.requireNonNull(actorType, "actorType es obligatorio");
            if (messageForCitizen == null || messageForCitizen.isBlank()) {
                throw new InvalidTicketRequestException("messageForCitizen es obligatorio");
            }
            Objects.requireNonNull(requestedAt, "requestedAt es obligatorio");
            Objects.requireNonNull(dueAt, "dueAt es obligatorio");
        }
    }
}
