package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.OutboxEventRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketSlaMilestoneService {
    private static final String MODULE_ID = "M2";

    private final TicketRepository ticketRepository;
    private final TicketSlaRepository slaRepository;
    private final TicketActivityRepository activityRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;
    private NotificationService notificationService;

    @Autowired
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCandidate(UUID ticketId, Long slaId, Instant effectiveNow) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId).orElse(null);
        if (ticket == null) {
            return;
        }
        slaRepository.findByIdForUpdate(slaId)
                .filter(sla -> sla.getTicket().getId().equals(ticketId))
                .filter(sla -> isSchedulerEligible(ticket, sla))
                .ifPresent(sla -> processMilestones(ticket, sla, effectiveNow));
    }

    /** El llamador debe mantener los locks pesimistas en orden Ticket -&gt; TicketSla. */
    public void processMilestones(Ticket ticket, TicketSla sla, Instant effectiveNow) {
        if (!isProcessable(sla)) {
            return;
        }

        Instant processedAt = clock.instant();
        String kind = sla.getSlaType() == SlaType.FIRST_RESPONSE ? "primera respuesta" : "resolución";
        if (sla.getStatus() == SlaStatus.RUNNING && !effectiveNow.isBefore(sla.getNearDueAt())) {
            sla.setStatus(SlaStatus.NEAR_DUE);
            saveActivity(ticket, sla, ActivityType.SLA_NEAR_DUE, "SLA_NEAR_DUE",
                    "El ticket alcanzó el umbral preventivo de su SLA de " + kind,
                    sla.getNearDueAt());
            if (notificationService != null) {
                notificationService.queueAssignedAgent(ticket, NotificationType.SLA_NEAR_DUE, null);
            }
        }

        if ((sla.getStatus() == SlaStatus.RUNNING || sla.getStatus() == SlaStatus.NEAR_DUE)
                && !effectiveNow.isBefore(sla.getDueAt())) {
            sla.setStatus(SlaStatus.BREACHED);
            saveActivity(ticket, sla, ActivityType.SLA_BREACHED, EscalationReasonCode.SLA_BREACHED.name(),
                    "El ticket incumplió su SLA de " + kind, sla.getDueAt());
            if (notificationService != null) {
                notificationService.queueAssignedAgent(ticket, NotificationType.SLA_BREACHED, null);
            }

            if (!ticket.isEscalated()) {
                ticket.setEscalated(true);
                ticket.setEscalationReasonCode(EscalationReasonCode.SLA_BREACHED);
                ticket.setEscalatedAt(sla.getDueAt());
                saveActivity(ticket, sla, ActivityType.ESCALATED, EscalationReasonCode.SLA_BREACHED.name(),
                        "Escalamiento automático por incumplimiento del SLA de " + kind, sla.getDueAt());
                if (shouldPublishEscalationChanged(ticket)) {
                    writeEscalationChangedEvent(ticket, processedAt);
                }
            }
        }
        slaRepository.save(sla);
        ticketRepository.save(ticket);
    }

    /** Caller holds the Ticket lock. Completed BREACHED cycles are history, not active causes. */
    public void reconcileEscalation(Ticket ticket, Instant now, boolean publish) {
        EscalationReasonCode previous = ticket.getEscalationReasonCode();
        boolean manual = ticket.isEscalated() && previous == EscalationReasonCode.MANUAL;
        boolean critical = ticket.getCurrentPriority() == Priority.CRITICAL;
        boolean breached = slaRepository.existsByTicket_IdAndCompletedAtIsNullAndStatus(
                ticket.getId(), SlaStatus.BREACHED);
        boolean valid = ticket.isEscalated() && (manual
                || previous == EscalationReasonCode.CRITICAL_PRIORITY && critical
                || previous == EscalationReasonCode.SLA_BREACHED && breached);
        if (valid) return; // An additional cause does not replace a still valid original reason.
        EscalationReasonCode next = manual ? EscalationReasonCode.MANUAL
                : breached ? EscalationReasonCode.SLA_BREACHED : critical ? EscalationReasonCode.CRITICAL_PRIORITY : null;
        if (!ticket.isEscalated() && next == null) return;
        boolean activation = !ticket.isEscalated() && next != null;
        ticket.setEscalated(next != null);
        ticket.setEscalationReasonCode(next);
        if (activation) ticket.setEscalatedAt(now);
        if (next == null) ticket.setEscalatedAt(null);
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(activation ? ActivityType.ESCALATED : ActivityType.STATE_CHANGED);
        activity.setPreviousStatus(ticket.getCurrentStatus());
        activity.setNewStatus(ticket.getCurrentStatus());
        activity.setActorType(ActorType.SYSTEM);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(next == null ? null : next.name());
        activity.setMessage(activation ? "Escalamiento automático por " + next
                : next == null ? "Escalamiento desactivado: no quedan causas vigentes"
                : "Motivo de escalamiento actualizado por pérdida de la causa anterior");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("escalationActive", ticket.isEscalated());
        metadata.put("previousReasonCode", previous == null ? null : previous.name());
        metadata.put("reasonCode", next == null ? null : next.name());
        activity.setMetadata(metadata);
        activity.setOccurredAt(now);
        activityRepository.save(activity);
        ticketRepository.save(ticket);
        if (publish && shouldPublishEscalationChanged(ticket)) writeEscalationChangedEvent(ticket, now);
    }

    private boolean isProcessable(TicketSla sla) {
        return sla.getCompletedAt() == null
                && sla.getPausedAt() == null
                && (sla.getStatus() == SlaStatus.RUNNING || sla.getStatus() == SlaStatus.NEAR_DUE);
    }

    private boolean isSchedulerEligible(Ticket ticket, TicketSla sla) {
        if (ticket.getMainTicket() != null || !isProcessable(sla)) {
            return false;
        }
        return switch (sla.getSlaType()) {
            case FIRST_RESPONSE -> ticket.getCurrentStatus() == TicketStatus.REGISTERED;
            case RESOLUTION -> switch (ticket.getCurrentStatus()) {
                case REGISTERED, IN_REVIEW, ROUTED, IN_PROGRESS -> true;
                default -> false;
            };
        };
    }

    private boolean shouldPublishEscalationChanged(Ticket ticket) {
        if (!ticket.isAnonymous()) {
            return true;
        }
        boolean alreadyUnderExternalManagement = ticket.getClassificationFinalizedAt() != null
                || ticket.getCurrentStatus() == TicketStatus.ROUTED
                || ticket.getCurrentStatus() == TicketStatus.IN_PROGRESS
                || ticket.getCurrentStatus() == TicketStatus.PENDING_INFORMATION;
        return alreadyUnderExternalManagement
                && ticket.getResponsibleAreaId() != null
                && !MODULE_ID.equalsIgnoreCase(ticket.getResponsibleAreaId());
    }

    private void saveActivity(Ticket ticket, TicketSla sla, ActivityType type, String reason,
                              String message, Instant occurredAt) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(type);
        activity.setPreviousStatus(ticket.getCurrentStatus());
        activity.setNewStatus(ticket.getCurrentStatus());
        activity.setActorType(ActorType.SYSTEM);
        activity.setActorId(null);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(reason);
        activity.setMessage(message);
        activity.setMetadata(Map.of("slaType", sla.getSlaType().name()));
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }

    private void writeEscalationChangedEvent(Ticket ticket, Instant updatedAt) {
        UUID eventId = UUID.randomUUID();

        Map<String, Object> escalation = new LinkedHashMap<>();
        escalation.put("active", ticket.isEscalated());
        escalation.put("reasonCode", ticket.getEscalationReasonCode() == null ? null : ticket.getEscalationReasonCode().name());
        escalation.put("escalatedAt", ticket.getEscalatedAt() == null ? null : ticket.getEscalatedAt().toString());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("escalation", escalation);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("publicId", ticket.getPublicId());
        data.put("citizenId", ticket.getCitizenId());
        data.put("isAnonymous", ticket.isAnonymous());
        data.put("responsibleAreaId", ticket.getResponsibleAreaId());
        data.put("updateType", TicketUpdatedType.ESCALATION_CHANGED.name());
        data.put("currentStatus", ticket.getCurrentStatus().name());
        data.put("currentPriority", ticket.getCurrentPriority().name());
        data.put("details", details);
        data.put("updatedAt", updatedAt.toString());
        Map<String, Object> payload = OutgoingEventEnvelope.create(eventId, "ticketUpdated", updatedAt, data);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType("ticketUpdated");
        event.setUpdateType(TicketUpdatedType.ESCALATION_CHANGED);
        event.setTicket(ticket);
        event.setPayload(payload);
        outboxEventRepository.save(event);
    }
}
