package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TicketSlaServiceTest {
    private static final Instant START = Instant.parse("2026-09-11T12:00:00Z");
    private static final Instant NEAR = Instant.parse("2026-09-11T20:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-11T22:00:00Z");
    private final TicketSlaRepository slas = mock(TicketSlaRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final SlaCalculationService calculations = mock(SlaCalculationService.class);
    private final ResolutionSlaMilestoneService milestones = mock(ResolutionSlaMilestoneService.class);
    private final TicketSlaService service = new TicketSlaService(slas, tickets, calculations, milestones);
    private Ticket ticket;
    private SlaPolicy policy;

    @BeforeEach void setUp() {
        reset(slas, tickets, calculations, milestones);
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTicketType(TicketType.REQUEST);
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setCreatedAt(START);
        policy = new SlaPolicy();
        policy.setId(7L);
        when(slas.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(calculations.calculateResolutionSchedule(any(), any(), any()))
                .thenReturn(Optional.of(new SlaCalculationService.SlaSchedule(policy, NEAR, DUE)));
    }

    @Test void creationStartsResolutionCycleOneAndSynchronizesProjection() {
        TicketSla sla = service.startInitialResolutionCycle(ticket, START).orElseThrow();

        assertEquals(SlaType.RESOLUTION, sla.getSlaType());
        assertEquals(1, sla.getCycleNumber());
        assertEquals(policy, sla.getPolicy());
        assertEquals(START, sla.getStartedAt());
        assertEquals(NEAR, sla.getNearDueAt());
        assertEquals(DUE, sla.getDueAt());
        assertEquals(SlaStatus.RUNNING, sla.getStatus());
        assertNull(sla.getCompletedAt());
        assertNull(sla.getPausedAt());
        assertEquals(0, sla.getTotalPausedSeconds());
        assertEquals(DUE, ticket.getResolutionDueAt());
    }

    @Test void initialReclassificationUpdatesTheSameCycleAndReconcilesImmediately() {
        TicketSla existing = cycle(1, SlaStatus.RUNNING);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(existing));

        TicketSla updated = service.recalculateInitialResolutionCycle(ticket, DUE).orElseThrow();

        assertSame(existing, updated);
        assertEquals(1, updated.getCycleNumber());
        assertEquals(DUE, ticket.getResolutionDueAt());
        verify(milestones).processResolutionSlaMilestones(ticket, existing, DUE);
    }

    @Test void resolutionBeforeDeadlineCompletesCycleAsMetUsingEffectiveTime() {
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        Instant resolvedAt = NEAR.minusSeconds(1);

        TicketSla completed = service.completeActiveResolutionCycle(ticket, resolvedAt).orElseThrow();

        assertEquals(SlaStatus.MET, completed.getStatus());
        assertEquals(resolvedAt, completed.getCompletedAt());
        verify(milestones).processResolutionSlaMilestones(ticket, active, resolvedAt);
    }

    @Test void initialReclassificationNeverRewritesCompletedHistory() {
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.empty());
        when(slas.findMaxCycleNumber(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(1));

        assertThrows(TicketStateConflictException.class,
                () -> service.recalculateInitialResolutionCycle(ticket, START));
        verify(slas, never()).save(any());
    }

    @Test void lateResolutionKeepsBreachedOutcomeAndCompletesCycle() {
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        doAnswer(invocation -> {
            ((TicketSla) invocation.getArgument(1)).setStatus(SlaStatus.BREACHED);
            return null;
        }).when(milestones).processResolutionSlaMilestones(ticket, active, DUE);

        TicketSla completed = service.completeActiveResolutionCycle(ticket, DUE).orElseThrow();

        assertEquals(SlaStatus.BREACHED, completed.getStatus());
        assertEquals(DUE, completed.getCompletedAt());
    }

    @Test void reopenUsesNextCycleNumberAndLeavesPreviousCycleUntouched() {
        TicketSla previous = cycle(1, SlaStatus.MET);
        previous.setCompletedAt(NEAR);
        when(slas.findMaxCycleNumber(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(1));

        TicketSla reopened = service.startReopenedResolutionCycle(ticket, START).orElseThrow();

        assertEquals(2, reopened.getCycleNumber());
        assertEquals(SlaStatus.RUNNING, reopened.getStatus());
        assertEquals(NEAR, previous.getCompletedAt());
        assertEquals(SlaStatus.MET, previous.getStatus());
    }

    @Test void missingResolutionPolicyDoesNotCreateInvalidCycle() {
        when(calculations.calculateResolutionSchedule(any(), any(), any())).thenReturn(Optional.empty());

        assertTrue(service.startInitialResolutionCycle(ticket, START).isEmpty());
        assertNull(ticket.getResolutionDueAt());
        verifyNoInteractions(slas);
    }

    private TicketSla cycle(int cycleNumber, SlaStatus status) {
        TicketSla sla = new TicketSla();
        sla.setTicket(ticket);
        sla.setSlaType(SlaType.RESOLUTION);
        sla.setCycleNumber(cycleNumber);
        sla.setPolicy(policy);
        sla.setStartedAt(START);
        sla.setNearDueAt(NEAR);
        sla.setDueAt(DUE);
        sla.setStatus(status);
        return sla;
    }
}
