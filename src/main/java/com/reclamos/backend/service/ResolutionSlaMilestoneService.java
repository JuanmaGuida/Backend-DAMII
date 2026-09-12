package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.OutboxEventRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
public class ResolutionSlaMilestoneService {
    private static final String MODULE_ID = "M2";

    private final TicketRepository ticketRepository;
    private final TicketSlaRepository slaRepository;
    private final TicketActivityRepository activityRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Value("${app.events.producer.module-id:M2}")
    private String producerModuleId = MODULE_ID;

    @Value("${app.events.producer.service:help-center-api}")
    private String producerService = "help-center-api";

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCandidate(UUID ticketId, Long slaId, Instant effectiveNow) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId).orElse(null);
        if (ticket == null) {
            return;
        }
        slaRepository.findByIdForUpdate(slaId)
                .filter(sla -> sla.getTicket().getId().equals(ticketId))
                .ifPresent(sla -> processResolutionSlaMilestones(ticket, sla, effectiveNow));
    }

    /** El llamador debe mantener los locks pesimistas en orden Ticket -&gt; TicketSla. */
    public void processResolutionSlaMilestones(Ticket ticket, TicketSla sla, Instant effectiveNow) {
        if (!hasActiveResolutionSla(ticket, sla)) {
            return;
        }

        Instant processedAt = clock.instant();
        if (sla.getStatus() == SlaStatus.RUNNING && !effectiveNow.isBefore(sla.getNearDueAt())) {
            sla.setStatus(SlaStatus.NEAR_DUE);
            saveActivity(ticket, ActivityType.SLA_NEAR_DUE, "SLA_NEAR_DUE",
                    "El ticket alcanzó el umbral preventivo de su SLA de resolución",
                    sla.getNearDueAt());
        }

        if ((sla.getStatus() == SlaStatus.RUNNING || sla.getStatus() == SlaStatus.NEAR_DUE)
                && !effectiveNow.isBefore(sla.getDueAt())) {
            sla.setStatus(SlaStatus.BREACHED);
            saveActivity(ticket, ActivityType.SLA_BREACHED, EscalationReasonCode.SLA_BREACHED.name(),
                    "El ticket incumplió su SLA de resolución", sla.getDueAt());

            if (!ticket.isEscalated()) {
                ticket.setEscalated(true);
                ticket.setEscalationReasonCode(EscalationReasonCode.SLA_BREACHED);
                ticket.setEscalatedAt(sla.getDueAt());
                saveActivity(ticket, ActivityType.ESCALATED, EscalationReasonCode.SLA_BREACHED.name(),
                        "Escalamiento automático por incumplimiento del SLA de resolución", sla.getDueAt());
                if (shouldPublishEscalationChanged(ticket)) {
                    writeEscalationChangedEvent(ticket, processedAt);
                }
            }
        }
        slaRepository.save(sla);
        ticketRepository.save(ticket);
    }

    private boolean hasActiveResolutionSla(Ticket ticket, TicketSla sla) {
        return sla.getSlaType() == SlaType.RESOLUTION
                && sla.getCompletedAt() == null
                && sla.getStatus() != SlaStatus.MET
                && sla.getStatus() != SlaStatus.BREACHED
                && ticket.getMainTicket() == null
                && switch (ticket.getCurrentStatus()) {
                    case REGISTERED, IN_REVIEW, ROUTED, IN_PROGRESS, PENDING_INFORMATION -> true;
                    default -> false;
                };
    }

    private boolean shouldPublishEscalationChanged(Ticket ticket) {
        return !ticket.isAnonymous()
                || (ticket.getResponsibleAreaId() != null
                && !MODULE_ID.equalsIgnoreCase(ticket.getResponsibleAreaId()));
    }

    private void saveActivity(Ticket ticket, ActivityType type, String reason, String message, Instant occurredAt) {
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
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }

    private void writeEscalationChangedEvent(Ticket ticket, Instant updatedAt) {
        UUID eventId = UUID.randomUUID();

        Map<String, Object> escalation = new LinkedHashMap<>();
        escalation.put("active", true);
        escalation.put("reasonCode", EscalationReasonCode.SLA_BREACHED.name());
        escalation.put("escalatedAt", ticket.getEscalatedAt().toString());
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
        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("moduleId", producerModuleId);
        producer.put("service", producerService);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("specVersion", "1.0");
        payload.put("eventId", eventId.toString());
        payload.put("eventType", "ticketUpdated");
        payload.put("occurredAt", updatedAt.toString());
        payload.put("producer", producer);
        payload.put("subject", "tickets/" + ticket.getId());
        payload.put("data", data);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType("ticketUpdated");
        event.setUpdateType(TicketUpdatedType.ESCALATION_CHANGED);
        event.setTicket(ticket);
        event.setPayload(payload);
        outboxEventRepository.save(event);
    }
}
