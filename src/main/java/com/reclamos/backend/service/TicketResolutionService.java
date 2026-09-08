package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.ResolveTicketRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketResolutionService {
    private static final String MODULE_ID = "M2";

    private final TicketRepository ticketRepository;
    private final TicketResolutionRepository resolutionRepository;
    private final TicketActivityRepository activityRepository;
    private final Clock clock;

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
        TicketResolution resolution = new TicketResolution();
        resolution.setTicket(ticket);
        resolution.setType(request.getType());
        resolution.setPublicMessage(request.getPublicMessage());
        resolution.setInternalMessage(request.getInternalMessage());
        resolution.setResolvedByType(actorType);
        resolution.setResolvedById(actorId);
        resolution.setResolvedByModuleId(MODULE_ID);
        resolution.setResolvedAt(now);
        resolution = resolutionRepository.save(resolution);

        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        ticket.setStatusChangedAt(now);
        ticketRepository.save(ticket);
        saveActivity(ticket, request, actorType, actorId, now);

        return new TicketResolutionResponse(resolution.getId(), ticket.getId(), TicketStatus.RESOLVED,
                resolution.getType(), resolution.getPublicMessage(), resolution.getInternalMessage(), now);
    }

    private void requireAgent(AuthenticatedIdentity identity) {
        if (identity == null || (identity.role() != ModuleRole.AGENT && identity.role() != ModuleRole.ADMIN)) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private void saveActivity(Ticket ticket, ResolveTicketRequest request, ActorType actorType,
                              String actorId, Instant occurredAt) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.RESOLVED);
        activity.setPreviousStatus(TicketStatus.IN_PROGRESS);
        activity.setNewStatus(TicketStatus.RESOLVED);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(request.getType().name());
        activity.setMessage(request.getPublicMessage());
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }
}