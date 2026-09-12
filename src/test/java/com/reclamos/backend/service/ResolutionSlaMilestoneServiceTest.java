package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.OutboxEventRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResolutionSlaMilestoneServiceTest {
    private static final Instant NEAR = Instant.parse("2026-09-10T10:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-10T12:00:00Z");
    private static final Instant PROCESSED = Instant.parse("2026-09-10T12:10:00Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketSlaRepository slas = mock(TicketSlaRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final ResolutionSlaMilestoneService service = new ResolutionSlaMilestoneService(
            tickets, slas, activities, outbox, Clock.fixed(PROCESSED, ZoneOffset.UTC));
    private Ticket ticket;
    private TicketSla sla;

    @BeforeEach void setUp() {
        reset(tickets, slas, activities, outbox);
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setPublicId("TK-2026-000001");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setResponsibleAreaId("M2");
        ticket.setResolutionDueAt(DUE);
        sla = new TicketSla();
        sla.setId(10L);
        sla.setTicket(ticket);
        sla.setSlaType(SlaType.RESOLUTION);
        sla.setCycleNumber(1);
        sla.setStartedAt(NEAR.minusSeconds(3600));
        sla.setNearDueAt(NEAR);
        sla.setDueAt(DUE);
        sla.setStatus(SlaStatus.RUNNING);
        when(activities.countByTicketId(ticket.getId())).thenReturn(0, 1, 2);
    }

    @Test void lateScanRecordsNearThenBreachThenEscalationExactlyOnce() {
        service.processResolutionSlaMilestones(ticket, sla, PROCESSED);
        service.processResolutionSlaMilestones(ticket, sla, PROCESSED.plusSeconds(60));

        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities, times(3)).save(captor.capture());
        List<TicketActivity> saved = captor.getAllValues();
        assertEquals(List.of(ActivityType.SLA_NEAR_DUE, ActivityType.SLA_BREACHED, ActivityType.ESCALATED),
                saved.stream().map(TicketActivity::getActionType).toList());
        assertEquals(List.of(1, 2, 3), saved.stream().map(TicketActivity::getSequence).toList());
        assertEquals(NEAR, saved.get(0).getOccurredAt());
        assertEquals(DUE, saved.get(1).getOccurredAt());
        assertEquals(DUE, saved.get(2).getOccurredAt());
        assertEquals(ActorType.SYSTEM, saved.get(0).getActorType());
        assertNull(saved.get(0).getActorId());
        assertEquals(SlaStatus.BREACHED, sla.getStatus());
        assertTrue(ticket.isEscalated());
        assertEquals(EscalationReasonCode.SLA_BREACHED, ticket.getEscalationReasonCode());
        assertEquals(DUE, ticket.getEscalatedAt());
        verifyNoInteractions(outbox);
    }

    @Test void criticalEscalationIsPreservedWhenSlaBreaches() {
        Instant escalatedAt = NEAR.minusSeconds(100);
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(escalatedAt);

        service.processResolutionSlaMilestones(ticket, sla, DUE);

        verify(activities, times(2)).save(any());
        assertEquals(SlaStatus.BREACHED, sla.getStatus());
        assertEquals(EscalationReasonCode.CRITICAL_PRIORITY, ticket.getEscalationReasonCode());
        assertEquals(escalatedAt, ticket.getEscalatedAt());
        verifyNoInteractions(outbox);
    }

    @Test void nearDueDoesNotEscalateOrChangeTicketStateOrPriority() {
        service.processResolutionSlaMilestones(ticket, sla, NEAR);

        assertEquals(SlaStatus.NEAR_DUE, sla.getStatus());
        assertFalse(ticket.isEscalated());
        assertEquals(TicketStatus.IN_PROGRESS, ticket.getCurrentStatus());
        assertEquals(Priority.HIGH, ticket.getCurrentPriority());
        verify(activities).save(argThat(a -> a.getActionType() == ActivityType.SLA_NEAR_DUE));
    }

    @Test void externallyReportedResolutionBeforeDueDoesNotCreateBreach() {
        service.processResolutionSlaMilestones(ticket, sla, DUE.minusNanos(1));

        assertEquals(SlaStatus.NEAR_DUE, sla.getStatus());
        assertFalse(ticket.isEscalated());
        verify(activities).save(argThat(a -> a.getActionType() == ActivityType.SLA_NEAR_DUE));
    }

    @Test void pendingInformationContinuesConsumingResolutionSla() {
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);

        service.processResolutionSlaMilestones(ticket, sla, DUE);

        assertEquals(SlaStatus.BREACHED, sla.getStatus());
        assertTrue(ticket.isEscalated());
    }

    @Test void beforeThresholdAndTerminalDuplicateOrCompletedCyclesAreIgnored() {
        service.processResolutionSlaMilestones(ticket, sla, NEAR.minusNanos(1));
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        service.processResolutionSlaMilestones(ticket, sla, PROCESSED);
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setMainTicket(new Ticket());
        service.processResolutionSlaMilestones(ticket, sla, PROCESSED);
        ticket.setMainTicket(null);
        sla.setCompletedAt(PROCESSED);
        sla.setStatus(SlaStatus.MET);
        service.processResolutionSlaMilestones(ticket, sla, PROCESSED);

        verifyNoInteractions(activities, outbox);
    }

    @Test void newlyEscalatedExternalTicketWritesCompleteOutboxEvent() {
        ticket.setResponsibleAreaId("M6");
        ticket.setCurrentStatus(TicketStatus.ROUTED);

        service.processResolutionSlaMilestones(ticket, sla, DUE);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        Map<String, Object> payload = captor.getValue().getPayload();
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertEquals(ticket.getId(), data.get("ticketId"));
        assertEquals(ticket.getPublicId(), data.get("publicId"));
        assertEquals(ticket.getCitizenId(), data.get("citizenId"));
        assertEquals(false, data.get("isAnonymous"));
        assertEquals(PROCESSED.toString(), data.get("updatedAt"));
        assertNotEquals(DUE.toString(), data.get("updatedAt"));
        assertTrue(data.containsKey("details"));
    }
}
