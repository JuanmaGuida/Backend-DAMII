package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.ReopenTicketRequest;
import com.reclamos.backend.dto.request.ResolveTicketRequest;
import com.reclamos.backend.dto.response.TicketResolutionActionResponse;
import com.reclamos.backend.dto.response.TicketResolutionResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketResolutionConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketResolutionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class TicketResolutionService {
    private static final String MODULE_ID = "M2";

    private final TicketRepository ticketRepository;
    private final TicketResolutionRepository resolutionRepository;
    private final TicketActivityRepository activityRepository;
    private final Clock clock;
    private final Duration confirmationDuration;
    private final TicketSlaService ticketSlaService;

    public TicketResolutionService(TicketRepository ticketRepository,
                                   TicketResolutionRepository resolutionRepository,
                                   TicketActivityRepository activityRepository,
                                   Clock clock,
                                   @Value("${ticket.resolution.confirmation-duration}") Duration confirmationDuration) {
        this(ticketRepository, resolutionRepository, activityRepository, clock, confirmationDuration, null);
    }

    @Autowired
    public TicketResolutionService(TicketRepository ticketRepository,
                                   TicketResolutionRepository resolutionRepository,
                                   TicketActivityRepository activityRepository,
                                   Clock clock,
                                   @Value("${ticket.resolution.confirmation-duration}") Duration confirmationDuration,
                                   TicketSlaService ticketSlaService) {
        this.ticketRepository = ticketRepository;
        this.resolutionRepository = resolutionRepository;
        this.activityRepository = activityRepository;
        this.clock = clock;
        if (confirmationDuration == null || confirmationDuration.isZero() || confirmationDuration.isNegative()) {
            throw new IllegalArgumentException("La duración de confirmación debe ser positiva");
        }
        this.confirmationDuration = confirmationDuration;
        this.ticketSlaService = ticketSlaService;
    }

    @Transactional
    public TicketResolutionResponse resolveManually(UUID ticketId, ResolveTicketRequest request,
                                                    AuthenticatedIdentity identity) {
        requireAgent(identity);
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
        if (Objects.equals(identity.citizenId(), ticket.getCitizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
        if (!MODULE_ID.equals(ticket.getResponsibleAreaId())) {
            throw new TicketResolutionConflictException(
                    "El ticket pertenece a un área externa y debe resolverse mediante el flujo de integración");
        }
        if (ticket.getCurrentStatus() != TicketStatus.IN_PROGRESS) {
            throw new TicketResolutionConflictException(
                    "El estado actual del ticket no permite su resolución manual");
        }

        Instant now = clock.instant();
        ActorType actorType = identity.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT;
        String actorId = identity.citizenId().toString();
        TicketResolution resolution = applyValidatedResolution(ticket, new ResolutionApplication(
                request.getType(), request.getPublicMessage(), request.getInternalMessage(),
                actorType, actorId, MODULE_ID, now, null));

        return new TicketResolutionResponse(resolution.getId(), ticket.getId(), TicketStatus.RESOLVED,
                resolution.getType(), resolution.getPublicMessage(), resolution.getInternalMessage(), now);
    }

    /**
     * Aplica los efectos de dominio comunes a una resolución cuyo origen,
     * estado y payload ya fueron validados por el flujo que la invoca.
     * El Ticket debe llegar bloqueado por {@code findByIdForUpdate}.
     */
    TicketResolution applyValidatedResolution(Ticket ticket, ResolutionApplication application) {
        Objects.requireNonNull(ticket, "ticket es obligatorio");
        Objects.requireNonNull(application, "application es obligatoria");

        TicketStatus previousStatus = ticket.getCurrentStatus();
        if (ticketSlaService != null) {
            ticketSlaService.completeActiveResolutionCycle(ticket, application.resolvedAt());
        }
        TicketResolution resolution = new TicketResolution();
        resolution.setTicket(ticket);
        resolution.setType(application.type());
        resolution.setPublicMessage(application.publicMessage());
        resolution.setInternalMessage(application.internalMessage());
        resolution.setResolvedByType(application.actorType());
        resolution.setResolvedById(application.actorId());
        resolution.setResolvedByModuleId(application.sourceModuleId());
        resolution.setResolvedAt(application.resolvedAt());
        resolution = resolutionRepository.save(resolution);

        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        ticket.setStatusChangedAt(application.resolvedAt());
        ticket.setResolutionConfirmationDueAt(application.resolvedAt().plus(confirmationDuration));
        ticketRepository.save(ticket);
        saveResolutionActivity(ticket, previousStatus, application);

        return resolution;
    }

    @Transactional
    public TicketResolutionActionResponse confirm(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = lockedTicket(ticketId);
        requireOwner(ticket, identity);
        requireResolved(ticket, "El estado actual del ticket no permite confirmar la resolución");

        Instant now = clock.instant();
        ticket.setCurrentStatus(TicketStatus.CLOSED);
        ticket.setStatusChangedAt(now);
        ticket.setResolutionConfirmationDueAt(null);
        ticketRepository.save(ticket);
        saveCitizenActivity(ticket, ActivityType.CLOSED, TicketStatus.CLOSED, identity,
                "CITIZEN_CONFIRMED", "El ciudadano confirmó la resolución", now);
        return actionResponse(ticket);
    }

    @Transactional
    public TicketResolutionActionResponse reopen(UUID ticketId, ReopenTicketRequest request,
                                                 AuthenticatedIdentity identity) {
        Ticket ticket = lockedTicket(ticketId);
        requireOwner(ticket, identity);
        requireResolved(ticket, "El estado actual del ticket no permite reabrir el ticket");

        Instant now = clock.instant();
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setStatusChangedAt(now);
        ticket.setReopenCount(ticket.getReopenCount() + 1);
        ticket.setResolutionConfirmationDueAt(null);
        if (ticketSlaService != null) {
            ticketSlaService.startReopenedResolutionCycle(ticket, now);
        }
        ticketRepository.save(ticket);
        saveCitizenActivity(ticket, ActivityType.REOPENED, TicketStatus.IN_PROGRESS, identity,
                null, request.getReason(), now);
        return actionResponse(ticket);
    }

    private void requireAgent(AuthenticatedIdentity identity) {
        if (identity == null || (identity.role() != ModuleRole.AGENT && identity.role() != ModuleRole.ADMIN)) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private Ticket lockedTicket(UUID ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
    }

    private void requireOwner(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null || ticket.isAnonymous() || ticket.getCitizenId() == null
                || !ticket.getCitizenId().equals(identity.citizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private void requireResolved(Ticket ticket, String message) {
        if (ticket.getCurrentStatus() != TicketStatus.RESOLVED) {
            throw new TicketResolutionConflictException(message);
        }
    }

    private void saveCitizenActivity(Ticket ticket, ActivityType actionType, TicketStatus newStatus,
                                     AuthenticatedIdentity identity, String reasonCode, String message,
                                     Instant occurredAt) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(actionType);
        activity.setPreviousStatus(TicketStatus.RESOLVED);
        activity.setNewStatus(newStatus);
        activity.setActorType(ActorType.CITIZEN);
        activity.setActorId(identity.citizenId().toString());
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(reasonCode);
        activity.setMessage(message);
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }

    private TicketResolutionActionResponse actionResponse(Ticket ticket) {
        return new TicketResolutionActionResponse(ticket.getId(), ticket.getCurrentStatus(),
                ticket.getStatusChangedAt(), ticket.getReopenCount());
    }

    private void saveResolutionActivity(Ticket ticket, TicketStatus previousStatus,
                                        ResolutionApplication application) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.RESOLVED);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(TicketStatus.RESOLVED);
        activity.setActorType(application.actorType());
        activity.setActorId(application.actorId());
        activity.setSourceModuleId(application.sourceModuleId());
        activity.setExternalEventId(application.externalEventId());
        activity.setReasonCode(application.type().name());
        activity.setMessage(application.publicMessage());
        activity.setOccurredAt(application.resolvedAt());
        activityRepository.save(activity);
    }

    record ResolutionApplication(
            ResolutionType type,
            String publicMessage,
            String internalMessage,
            ActorType actorType,
            String actorId,
            String sourceModuleId,
            Instant resolvedAt,
            UUID externalEventId
    ) {
        public ResolutionApplication {
            Objects.requireNonNull(type, "type es obligatorio");
            if (publicMessage == null || publicMessage.isBlank()) {
                throw new IllegalArgumentException("publicMessage es obligatorio");
            }
            Objects.requireNonNull(actorType, "actorType es obligatorio");
            Objects.requireNonNull(sourceModuleId, "sourceModuleId es obligatorio");
            Objects.requireNonNull(resolvedAt, "resolvedAt es obligatorio");
        }
    }
}
