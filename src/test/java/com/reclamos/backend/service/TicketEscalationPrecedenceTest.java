package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TicketEscalationPrecedenceTest {
    private static final Instant ACTIVATED = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant DUE = ACTIVATED.plusSeconds(3600);
    private static final Instant NOW = DUE.plusSeconds(60);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketSlaRepository slas = mock(TicketSlaRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final TicketSlaMilestoneService service = new TicketSlaMilestoneService(
            tickets, slas, activities, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    private Ticket ticket;
    private TicketSla sla;

    @BeforeEach void setup() {
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setPublicId("TK-2026-000002");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setResponsibleAreaId("M6");
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        ticket.setCurrentPriority(Priority.CRITICAL);
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(ACTIVATED);
        sla = new TicketSla();
        sla.setTicket(ticket);
        sla.setSlaType(SlaType.RESOLUTION);
        sla.setStartedAt(ACTIVATED);
        sla.setNearDueAt(DUE.minusSeconds(600));
        sla.setDueAt(DUE);
        sla.setStatus(SlaStatus.NEAR_DUE);
        service.setNotificationService(notifications);
        when(slas.existsByTicket_IdAndCompletedAtIsNullAndStatus(ticket.getId(), SlaStatus.BREACHED))
                .thenAnswer(invocation -> sla.getCompletedAt() == null && sla.getStatus() == SlaStatus.BREACHED);
    }

    @ParameterizedTest @EnumSource(SlaType.class)
    void criticalThenBreachChangesOnlyReasonAndAuditsAndPublishesOnce(SlaType type) {
        sla.setSlaType(type);
        service.processMilestones(ticket, sla, NOW);
        service.processMilestones(ticket, sla, NOW.plusSeconds(1));
        service.reconcileEscalation(ticket, NOW.plusSeconds(2), true);

        assertThat(ticket.isEscalated()).isTrue();
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.SLA_BREACHED);
        assertThat(ticket.getEscalatedAt()).isEqualTo(ACTIVATED);
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.REGISTERED);
        assertThat(ticket.getCurrentPriority()).isEqualTo(Priority.CRITICAL);
        assertThat(sla.getStartedAt()).isEqualTo(ACTIVATED);
        assertThat(sla.getNearDueAt()).isEqualTo(DUE.minusSeconds(600));
        assertThat(sla.getDueAt()).isEqualTo(DUE);
        ArgumentCaptor<TicketActivity> audit = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activities, times(2)).save(audit.capture());
        assertThat(audit.getAllValues()).extracting(TicketActivity::getActionType)
                .containsExactly(ActivityType.SLA_BREACHED, ActivityType.STATE_CHANGED);
        TicketActivity change = audit.getAllValues().getLast();
        assertThat(change.getPreviousStatus()).isEqualTo(change.getNewStatus());
        assertThat(change.getMetadata()).containsEntry("previousReasonCode", "CRITICAL_PRIORITY")
                .containsEntry("reasonCode", "SLA_BREACHED");
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(event.capture());
        PriorityEventContractAssertions.assertFullEventValid(event.getValue().getPayload());
        assertThat(event.getValue().getUpdateType()).isEqualTo(TicketUpdatedType.ESCALATION_CHANGED);
        verify(notifications).queueAssignedAgent(ticket, NotificationType.SLA_BREACHED, null);
        verifyNoMoreInteractions(notifications);
    }

    @ParameterizedTest @EnumSource(SlaType.class)
    void manualThenBreachPreservesReasonAndDateWithoutFalseChange(SlaType type) {
        sla.setSlaType(type);
        ticket.setEscalationReasonCode(EscalationReasonCode.MANUAL);
        service.processMilestones(ticket, sla, NOW);
        service.reconcileEscalation(ticket, NOW, true);
        service.processMilestones(ticket, sla, NOW.plusSeconds(1));
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.MANUAL);
        assertThat(ticket.getEscalatedAt()).isEqualTo(ACTIVATED);
        verify(activities).save(argThat(a -> a.getActionType() == ActivityType.SLA_BREACHED));
        verifyNoInteractions(outbox);
        verify(notifications).queueAssignedAgent(ticket, NotificationType.SLA_BREACHED, null);
    }

    @ParameterizedTest @CsvSource({"false,CRITICAL_PRIORITY", "true,SLA_BREACHED"})
    void ordinaryAndReclassificationSelectSameHighestCause(boolean breached, EscalationReasonCode expected) {
        sla.setStatus(breached ? SlaStatus.BREACHED : SlaStatus.RUNNING);
        service.reconcileEscalation(ticket, NOW, true);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(expected);
        service.reconcileReclassificationEscalation(ticket, NOW);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(expected);
        assertThat(ticket.getEscalatedAt()).isEqualTo(ACTIVATED);
        if (breached) {
            verify(activities).save(any());
            verify(outbox).save(any());
        } else verifyNoInteractions(activities, outbox, tickets);
    }

    @ParameterizedTest @CsvSource({
            "MANUAL,CRITICAL,false,MANUAL", "MANUAL,CRITICAL,true,MANUAL",
            "SLA_BREACHED,CRITICAL,true,SLA_BREACHED", "SLA_BREACHED,LOW,true,SLA_BREACHED",
            "SLA_BREACHED,CRITICAL,false,CRITICAL_PRIORITY", "CRITICAL_PRIORITY,LOW,false,NULL",
            "CRITICAL_PRIORITY,LOW,true,SLA_BREACHED"})
    void reconciliationUsesOnlyCurrentCauses(EscalationReasonCode previous, Priority priority,
                                             boolean breached, String expected) {
        ticket.setEscalationReasonCode(previous);
        ticket.setCurrentPriority(priority);
        sla.setStatus(breached ? SlaStatus.BREACHED : SlaStatus.RUNNING);
        EscalationReasonCode next = expected.equals("NULL") ? null : EscalationReasonCode.valueOf(expected);
        service.reconcileEscalation(ticket, NOW, true);
        service.reconcileEscalation(ticket, NOW.plusSeconds(1), true);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(next);
        assertThat(ticket.isEscalated()).isEqualTo(next != null);
        assertThat(ticket.getEscalatedAt()).isEqualTo(next == null ? null : ACTIVATED);
        assertThat(ticket.getCurrentPriority()).isEqualTo(priority);
        if (previous == next) verifyNoInteractions(activities, outbox, tickets);
        else {
            verify(activities).save(argThat(a -> a.getActionType() == ActivityType.STATE_CHANGED));
            verify(outbox).save(any());
            verify(tickets).save(ticket);
        }
    }

    @ParameterizedTest @EnumSource(SlaType.class)
    void completedBreachFallsBackToCriticalAndKeepsHistoricalCycle(SlaType type) {
        sla.setSlaType(type);
        sla.setStatus(SlaStatus.BREACHED);
        sla.setCompletedAt(DUE);
        ticket.setEscalationReasonCode(EscalationReasonCode.SLA_BREACHED);
        service.reconcileEscalation(ticket, NOW, true);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.CRITICAL_PRIORITY);
        assertThat(ticket.getEscalatedAt()).isEqualTo(ACTIVATED);
        assertThat(sla.getStatus()).isEqualTo(SlaStatus.BREACHED);
        assertThat(sla.getCompletedAt()).isEqualTo(DUE);
        verify(slas, never()).save(any());
    }

    @ParameterizedTest @CsvSource({"true,CRITICAL,SLA_BREACHED", "false,CRITICAL,CRITICAL_PRIORITY", "false,LOW,NULL"})
    void explicitlyWithdrawnManualFallsBackWithoutAnotherActivation(boolean breached, Priority priority, String expected) {
        // No manual command exists in this backend. Model explicit withdrawal at the entity boundary,
        // keeping the continuous activation while clearing the manual cause before reconciliation.
        ticket.setEscalationReasonCode(null);
        ticket.setCurrentPriority(priority);
        sla.setStatus(breached ? SlaStatus.BREACHED : SlaStatus.RUNNING);
        service.reconcileEscalation(ticket, NOW, true);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(expected.equals("NULL")
                ? null : EscalationReasonCode.valueOf(expected));
        assertThat(ticket.getEscalatedAt()).isEqualTo(expected.equals("NULL") ? null : ACTIVATED);
        verify(activities, never()).save(argThat(a -> a.getActionType() == ActivityType.ESCALATED));
    }

    @ParameterizedTest @CsvSource({"CRITICAL,LOW,false,NULL", "LOW,CRITICAL,false,CRITICAL_PRIORITY",
            "CRITICAL,LOW,true,SLA_BREACHED", "LOW,CRITICAL,true,SLA_BREACHED"})
    void reclassificationCanRaiseOrLowerPriorityAndReconcilesCauses(Priority before, Priority after,
                                                                   boolean breached, String expected) {
        ticket.setCurrentPriority(before);
        ticket.setEscalated(before == Priority.CRITICAL);
        ticket.setEscalationReasonCode(before == Priority.CRITICAL ? EscalationReasonCode.CRITICAL_PRIORITY : null);
        ticket.setEscalatedAt(before == Priority.CRITICAL ? ACTIVATED : null);
        sla.setStatus(breached ? SlaStatus.BREACHED : SlaStatus.RUNNING);
        ticket.setCurrentPriority(after);
        service.reconcileReclassificationEscalation(ticket, NOW);
        service.reconcileReclassificationEscalation(ticket, NOW.plusSeconds(1));
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(expected.equals("NULL")
                ? null : EscalationReasonCode.valueOf(expected));
        assertThat(ticket.getCurrentPriority()).isEqualTo(after);
        assertThat(ticket.getEscalatedAt()).isEqualTo(expected.equals("NULL") ? null
                : before == Priority.CRITICAL ? ACTIVATED : NOW);
        verify(activities).save(any());
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest @CsvSource({"false,M2,REGISTERED,true", "true,M2,IN_PROGRESS,false",
            "true,M6,REGISTERED,false", "true,M6,ROUTED,true"})
    void reasonChangeUsesExistingPublicationPolicy(boolean anonymous, String area, TicketStatus status, boolean publish) {
        ticket.setAnonymous(anonymous);
        if (anonymous) ticket.setCitizenId(null);
        ticket.setResponsibleAreaId(area);
        ticket.setCurrentStatus(status);
        service.processMilestones(ticket, sla, NOW);
        assertThat(ticket.getEscalationReasonCode()).isEqualTo(EscalationReasonCode.SLA_BREACHED);
        verify(outbox, times(publish ? 1 : 0)).save(any());
        verify(activities, times(2)).save(any());
    }
}
