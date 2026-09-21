package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.LinkDuplicateRequest;
import com.reclamos.backend.dto.response.DuplicateLinkResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DuplicateTicketService {
    private static final String MODULE_ID = "M2";
    private static final Set<TicketStatus> ACTIVE_MAIN_STATUSES = Set.of(
            TicketStatus.REGISTERED, TicketStatus.IN_REVIEW, TicketStatus.ROUTED,
            TicketStatus.IN_PROGRESS, TicketStatus.PENDING_INFORMATION);

    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketSlaService ticketSlaService;
    private final TicketOutboxService ticketOutboxService;
    private final TicketService ticketService;
    private final Clock clock;

    @Transactional
    public DuplicateLinkResponse link(UUID duplicateId, LinkDuplicateRequest request,
                                      AuthenticatedIdentity actor) {
        UUID mainId = request.getMainTicketId();
        if (duplicateId.equals(mainId)) {
            throw new TicketStateConflictException("Un ticket no puede vincularse consigo mismo");
        }

        // Orden estable de locks: evita deadlocks y hace atómica la validación A -> B / B -> A.
        UUID firstId = duplicateId.compareTo(mainId) < 0 ? duplicateId : mainId;
        UUID secondId = firstId.equals(duplicateId) ? mainId : duplicateId;
        Ticket first = lock(firstId, firstId.equals(duplicateId) ? "Ticket no encontrado" : "Ticket principal no encontrado");
        Ticket second = lock(secondId, secondId.equals(duplicateId) ? "Ticket no encontrado" : "Ticket principal no encontrado");
        Ticket duplicate = firstId.equals(duplicateId) ? first : second;
        Ticket main = firstId.equals(mainId) ? first : second;

        ticketService.requireTriageAuthority(duplicate, actor);
        if (duplicate.getMainTicket() != null) {
            if (duplicate.getMainTicket().getId().equals(mainId)) {
                return response(duplicate); // repetición idempotente, sin actividad ni evento nuevos
            }
            throw new TicketStateConflictException("El ticket ya está vinculado a otro principal");
        }
        if (duplicate.getCurrentStatus() == TicketStatus.DUPLICATE) {
            throw new TicketStateConflictException("El ticket duplicado no tiene un vínculo principal válido");
        }
        if (main.getMainTicket() != null || main.getCurrentStatus() == TicketStatus.DUPLICATE) {
            throw new TicketStateConflictException("El principal no puede ser a su vez un duplicado");
        }
        if (!ACTIVE_MAIN_STATUSES.contains(main.getCurrentStatus())) {
            throw new TicketStateConflictException("El estado del ticket principal no permite vincular duplicados");
        }

        TicketStatus previous = duplicate.getCurrentStatus();
        Instant now = clock.instant();
        duplicate.setMainTicket(main);
        duplicate.setCurrentStatus(TicketStatus.DUPLICATE);
        duplicate.setStatusChangedAt(now);
        ticketSlaService.stopActiveCyclesForDuplicate(duplicate, now);
        ticketRepository.save(duplicate);
        saveActivity(duplicate, ActivityType.DUPLICATE_LINKED, previous, TicketStatus.DUPLICATE,
                actor.role() == com.reclamos.backend.identity.ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT,
                actor.citizenId().toString(), "Vinculado al ticket principal " + mainId, now);
        // estimatedAffectedCount del principal es deliberadamente inmutable: deriva de barrio x factor.
        ticketOutboxService.duplicateLinked(duplicate, now);
        return response(duplicate);
    }

    @Transactional
    public void propagateClosed(Ticket main, ActorType actorType, String actorId,
                                String reasonCode, Instant closedAt) {
        for (Ticket duplicate : ticketRepository.findActiveDuplicatesForUpdate(main.getId())) {
            duplicate.setCurrentStatus(TicketStatus.CLOSED);
            duplicate.setStatusChangedAt(closedAt);
            ticketRepository.save(duplicate);
            saveActivity(duplicate, ActivityType.CLOSED, TicketStatus.DUPLICATE, TicketStatus.CLOSED,
                    actorType, actorId, "Cierre heredado del ticket principal " + main.getId(), closedAt,
                    reasonCode);
            ticketOutboxService.closed(duplicate, reasonCode, closedAt);
        }
    }

    private Ticket lock(UUID id, String message) {
        return ticketRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(message));
    }

    private DuplicateLinkResponse response(Ticket ticket) {
        return new DuplicateLinkResponse(ticket.getId(), ticket.getMainTicket().getId(), ticket.getCurrentStatus());
    }

    private void saveActivity(Ticket ticket, ActivityType type, TicketStatus previous, TicketStatus next,
                              ActorType actorType, String actorId, String message, Instant at) {
        saveActivity(ticket, type, previous, next, actorType, actorId, message, at, null);
    }

    private void saveActivity(Ticket ticket, ActivityType type, TicketStatus previous, TicketStatus next,
                              ActorType actorType, String actorId, String message, Instant at, String reasonCode) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(type);
        activity.setPreviousStatus(previous);
        activity.setNewStatus(next);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(reasonCode);
        activity.setMessage(message);
        activity.setOccurredAt(at);
        activityRepository.save(activity);
    }
}