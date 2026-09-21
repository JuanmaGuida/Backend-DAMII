package com.reclamos.backend.service;

import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ResolutionConfirmationTimeoutService {
    private static final String MODULE_ID = "M2";
    private static final String TIMEOUT_REASON = "CONFIRMATION_TIMEOUT";
    private static final String TIMEOUT_MESSAGE =
            "El ticket fue cerrado automáticamente por falta de respuesta dentro del plazo de confirmación";

    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final DuplicateTicketService duplicateTicketService;
    private final TicketOutboxService ticketOutboxService;

    @Autowired
    public ResolutionConfirmationTimeoutService(TicketRepository ticketRepository,
                                                TicketActivityRepository activityRepository,
                                                DuplicateTicketService duplicateTicketService,
                                                TicketOutboxService ticketOutboxService) {
        this.ticketRepository = ticketRepository;
        this.activityRepository = activityRepository;
        this.duplicateTicketService = duplicateTicketService;
        this.ticketOutboxService = ticketOutboxService;
    }

    ResolutionConfirmationTimeoutService(TicketRepository ticketRepository,
                                         TicketActivityRepository activityRepository) {
        this(ticketRepository, activityRepository, null, null);
    }

    @Transactional
    public void closeIfConfirmationExpired(UUID ticketId, Instant now) {
        ticketRepository.findByIdForUpdate(ticketId).ifPresent(ticket -> closeIfStillExpired(ticket, now));
    }

    private void closeIfStillExpired(Ticket ticket, Instant now) {
        if (ticket.getCurrentStatus() != TicketStatus.RESOLVED
                || ticket.getResolutionConfirmationDueAt() == null
                || ticket.getResolutionConfirmationDueAt().isAfter(now)) {
            return;
        }

        ticket.setCurrentStatus(TicketStatus.CLOSED);
        ticket.setStatusChangedAt(now);
        ticket.setResolutionConfirmationDueAt(null);
        ticketRepository.save(ticket);

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.CLOSED);
        activity.setPreviousStatus(TicketStatus.RESOLVED);
        activity.setNewStatus(TicketStatus.CLOSED);
        activity.setActorType(ActorType.SYSTEM);
        activity.setActorId(null);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(TIMEOUT_REASON);
        activity.setMessage(TIMEOUT_MESSAGE);
        activity.setOccurredAt(now);
        activityRepository.save(activity);
        if (ticketOutboxService != null) {
            ticketOutboxService.closed(ticket, TIMEOUT_REASON, now);
        }
        if (duplicateTicketService != null) {
            duplicateTicketService.propagateClosed(ticket, ActorType.SYSTEM, null, TIMEOUT_REASON, now);
        }
    }
}