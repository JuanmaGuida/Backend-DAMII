package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketPriorityService {
    public static final Set<TicketStatus> ACTIVE_STATUSES = Set.of(TicketStatus.REGISTERED, TicketStatus.IN_REVIEW,
            TicketStatus.ROUTED, TicketStatus.IN_PROGRESS, TicketStatus.PENDING_INFORMATION);
    private final TicketRepository tickets;
    private final TicketSlaRepository slas;
    private final TicketActivityRepository activities;
    private final RiskCalculationService risks;
    private final PriorityCalculationService calculation;
    private final TicketSlaMilestoneService milestones;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCandidate(UUID id, Instant now) {
        Ticket ticket = tickets.findByIdForUpdate(id).orElse(null);
        if (ticket == null || ticket.getMainTicket() != null || !ACTIVE_STATUSES.contains(ticket.getCurrentStatus())) return;
        TicketSla sla = slas.findActiveForUpdate(id, SlaType.RESOLUTION).orElse(null);
        Priority previous = ticket.getCurrentPriority();
        Priority calculated = calculation.calculate(risks.calculateRisk(ticket).score(), ticket.getEstimatedAffectedCount(),
                calculation.age(sla, now), ticket.getRequestType().getMinimumPriority());
        Priority next = PriorityCalculationService.max(previous, calculated);
        if (next != previous) {
            ticket.setCurrentPriority(next);
            TicketActivity activity = new TicketActivity();
            activity.setTicket(ticket);
            activity.setSequence(activities.countByTicketId(id) + 1);
            activity.setActionType(ActivityType.PRIORITY_CHANGED);
            activity.setPreviousStatus(ticket.getCurrentStatus());
            activity.setNewStatus(ticket.getCurrentStatus());
            activity.setPreviousPriority(previous);
            activity.setNewPriority(next);
            activity.setActorType(ActorType.SYSTEM);
            activity.setSourceModuleId("M2");
            activity.setMessage("Prioridad incrementada por recálculo automático");
            activity.setOccurredAt(now);
            activities.save(activity);
            tickets.save(ticket);
        }
        // No PRIORITY_CHANGED event and no changes to any SLA schedule.
        milestones.reconcileEscalation(ticket, now, true);
    }
}
