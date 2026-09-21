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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

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
    private final AttachmentService attachmentService;
    private final AttachmentReferenceService attachmentReferenceService;
    private final InformationRequestAttachmentRepository informationRequestAttachmentRepository;
    private final NotificationService notificationService;

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
        Instant m2Now = deadlineService.now();
        Instant defaultDueAt = deadlineService.calculateDueAt(m2Now);
        if (requiredBy != null && !requiredBy.isAfter(m2Now)) {
            throw new InvalidTicketRequestException(
                    "details.informationRequest.requiredBy debe ser posterior al momento actual de M2");
        }
        Instant dueAt = requiredBy == null || requiredBy.isAfter(defaultDueAt) ? defaultDueAt : requiredBy;
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
        notificationService.queue(ticket, NotificationType.INFORMATION_REQUIRED);
        return informationRequest;
    }

    @Transactional
    public InformationRequestResponse answerInformation(UUID ticketId, AnswerInformationRequest request,
                                                         AuthenticatedIdentity identity) {
        return answerInformation(ticketId, request, identity, new MultipartFile[0]);
    }

    @Transactional
    public InformationRequestResponse answerInformation(UUID ticketId, AnswerInformationRequest request,
                                                         AuthenticatedIdentity identity,
                                                         MultipartFile[] attachments) {
        if (identity == null) throw new UnauthorizedTicketOperationException();
        List<AttachmentService.ValidatedAttachment> validated = validateAnswer(request, attachments);
        Ticket ticket = lockedTicket(ticketId);
        if (ticket.isAnonymous() || ticket.getCitizenId() == null
                || !ticket.getCitizenId().equals(identity.citizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
        return answerPending(ticket, normalizedMessage(request), identity.citizenId().toString(), identity, validated);
    }

    @Transactional
    public InformationRequestResponse answerAnonymousFromTracking(UUID ticketId, AnswerInformationRequest request) {
        return answerAnonymousFromTracking(ticketId, request, new MultipartFile[0]);
    }

    @Transactional
    public InformationRequestResponse answerAnonymousFromTracking(UUID ticketId, AnswerInformationRequest request,
                                                                  MultipartFile[] attachments) {
        List<AttachmentService.ValidatedAttachment> validated = validateAnswer(request, attachments);
        Ticket ticket = lockedTicket(ticketId);
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null) {
            throw new UnauthorizedTicketOperationException();
        }
        return answerPending(ticket, normalizedMessage(request), null, null, validated);
    }

    private InformationRequestResponse answerPending(Ticket ticket, String responseMessage, String actorId,
                                                     AuthenticatedIdentity identity,
                                                     List<AttachmentService.ValidatedAttachment> validatedAttachments) {
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
        List<Attachment> storedAttachments = attachmentService.storeForTicket(
                ticket, identity, validatedAttachments, answeredAt);
        List<InformationRequestAttachment> links = storedAttachments.stream().map(attachment -> {
            InformationRequestAttachment link = new InformationRequestAttachment();
            link.setInformationRequest(informationRequest);
            link.setAttachment(attachment);
            link.setRole(InformationAttachmentRole.RESPONSE);
            return link;
        }).toList();
        informationRequestAttachmentRepository.saveAll(links);
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
        ticketOutboxService.informationProvided(ticket, responseMessage, storedAttachments,
                ActorType.CITIZEN, actorId,
                !MODULE_ID.equalsIgnoreCase(informationRequest.getRequestedByModuleId()), answeredAt);
        return response(informationRequest, storedAttachments);
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
        return response(request, List.of());
    }

    private InformationRequestResponse response(InformationRequest request, List<Attachment> attachments) {
        return new InformationRequestResponse(request.getStatus(), request.getTicket().getCurrentStatus(),
                request.getMessageForCitizen(), request.getRequestedAt(), request.getDueAt(),
                request.getResponseMessage(), request.getAnsweredAt(),
                attachments.stream().map(attachment -> new com.reclamos.backend.dto.response.TicketAttachmentResponse(
                        attachment.getId(), attachment.getFileName(), attachment.getContentType(),
                        attachment.getSizeBytes(), attachment.getVisibility(), attachment.getCreatedAt(),
                        attachmentReferenceService.downloadUrl(attachment))).toList());
    }

    private List<AttachmentService.ValidatedAttachment> validateAnswer(
            AnswerInformationRequest request, MultipartFile[] attachments) {
        if (request == null) {
            throw new InvalidTicketRequestException("La respuesta de información es obligatoria");
        }
        List<AttachmentService.ValidatedAttachment> validated = attachmentService.validate(attachments);
        if ((request.getResponseMessage() == null || request.getResponseMessage().isBlank()) && validated.isEmpty()) {
            throw new InvalidTicketRequestException("La respuesta debe incluir texto o al menos un archivo");
        }
        return validated;
    }

    private String normalizedMessage(AnswerInformationRequest request) {
        return request.getResponseMessage() == null || request.getResponseMessage().isBlank()
                ? null : request.getResponseMessage().strip();
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
