package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import com.reclamos.backend.repository.SlaPolicyRepository;
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
    private final TicketSlaMilestoneService milestones = mock(TicketSlaMilestoneService.class);
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

    @Test void creationStartsOnlyFirstResponseCycleOneWhenPolicyExists() {
        when(calculations.calculateSchedule(START, Priority.HIGH, SlaType.FIRST_RESPONSE))
                .thenReturn(Optional.of(new SlaCalculationService.SlaSchedule(policy, NEAR, DUE)));

        TicketSla sla = service.startFirstResponseCycle(ticket, START).orElseThrow();

        assertEquals(SlaType.FIRST_RESPONSE, sla.getSlaType());
        assertEquals(1, sla.getCycleNumber());
        assertEquals(SlaStatus.RUNNING, sla.getStatus());
        assertEquals(DUE, ticket.getFirstResponseDueAt());
    }

    @Test void missingFirstResponsePolicyDoesNotCreateCycle() {
        when(calculations.calculateSchedule(START, Priority.HIGH, SlaType.FIRST_RESPONSE))
                .thenReturn(Optional.empty());

        assertTrue(service.startFirstResponseCycle(ticket, START).isEmpty());
        assertNull(ticket.getFirstResponseDueAt());
        verify(slas, never()).save(any());
    }

    @Test void firstResponseIsNeverCreatedAsCycleTwo() {
        TicketSla existing = cycle(1, SlaStatus.MET);
        existing.setSlaType(SlaType.FIRST_RESPONSE);
        existing.setCompletedAt(NEAR);
        when(slas.findMaxCycleNumber(ticket.getId(), SlaType.FIRST_RESPONSE)).thenReturn(Optional.of(1));
        when(slas.findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(ticket.getId(), SlaType.FIRST_RESPONSE))
                .thenReturn(Optional.of(existing));

        assertSame(existing, service.startFirstResponseCycle(ticket, START).orElseThrow());
        verify(calculations, never()).calculateSchedule(any(), any(), eq(SlaType.FIRST_RESPONSE));
        verify(slas, never()).save(any());
    }

    @Test void firstReviewCompletesFirstResponseAndPreservesBreachedOutcome() {
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        active.setSlaType(SlaType.FIRST_RESPONSE);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.FIRST_RESPONSE)).thenReturn(Optional.of(active));
        doAnswer(invocation -> {
            active.setStatus(SlaStatus.BREACHED);
            return null;
        }).when(milestones).processMilestones(ticket, active, DUE);

        TicketSla completed = service.completeFirstResponseCycle(ticket, DUE).orElseThrow();

        assertEquals(SlaStatus.BREACHED, completed.getStatus());
        assertEquals(DUE, completed.getCompletedAt());
        assertNull(completed.getPausedAt());
    }

    @Test void initialReclassificationUpdatesTheSameCycleAndReconcilesImmediately() {
        TicketSla existing = cycle(1, SlaStatus.RUNNING);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(existing));

        TicketSla updated = service.recalculateInitialResolutionCycle(ticket, DUE).orElseThrow();

        assertSame(existing, updated);
        assertEquals(1, updated.getCycleNumber());
        assertEquals(DUE, ticket.getResolutionDueAt());
        verify(milestones).processMilestones(ticket, existing, DUE);
    }

    @Test void resolutionBeforeDeadlineCompletesCycleAsMetUsingEffectiveTime() {
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        Instant resolvedAt = NEAR.minusSeconds(1);

        TicketSla completed = service.completeActiveResolutionCycle(ticket, resolvedAt).orElseThrow();

        assertEquals(SlaStatus.MET, completed.getStatus());
        assertEquals(resolvedAt, completed.getCompletedAt());
        verify(milestones).processMilestones(ticket, active, resolvedAt);
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
        }).when(milestones).processMilestones(ticket, active, DUE);

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

    @Test void pauseAndResumeMoveRemainingDeadlinesAndAccumulateEffectiveSeconds() {
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        Instant pausedAt = START.plusSeconds(600);
        Instant resumedAt = START.plusSeconds(4200);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        when(calculations.effectiveSecondsBetween(pausedAt, resumedAt, policy)).thenReturn(3600L);
        when(calculations.effectiveSecondsBetween(pausedAt, NEAR, policy)).thenReturn(7200L);
        when(calculations.effectiveSecondsBetween(pausedAt, DUE, policy)).thenReturn(9000L);
        when(calculations.addEffectiveSeconds(resumedAt, 7200L, policy)).thenReturn(NEAR.plusSeconds(3600));
        when(calculations.addEffectiveSeconds(resumedAt, 9000L, policy)).thenReturn(DUE.plusSeconds(3600));

        service.pauseActiveResolutionCycle(ticket, pausedAt);
        TicketSla resumed = service.resumeActiveResolutionCycle(ticket, resumedAt).orElseThrow();

        assertNull(resumed.getPausedAt());
        assertEquals(3600, resumed.getTotalPausedSeconds());
        assertEquals(NEAR.plusSeconds(3600), resumed.getNearDueAt());
        assertEquals(DUE.plusSeconds(3600), resumed.getDueAt());
        assertEquals(SlaStatus.RUNNING, resumed.getStatus());
    }

    @Test void nearDuePauseKeepsHistoricalNearMilestoneAndMovesOnlyDueAt() {
        TicketSla active = cycle(1, SlaStatus.NEAR_DUE);
        Instant pausedAt = START.plusSeconds(600);
        Instant resumedAt = START.plusSeconds(4200);
        active.setPausedAt(pausedAt);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        when(calculations.effectiveSecondsBetween(pausedAt, resumedAt, policy)).thenReturn(3600L);
        when(calculations.effectiveSecondsBetween(pausedAt, DUE, policy)).thenReturn(9000L);
        when(calculations.addEffectiveSeconds(resumedAt, 9000L, policy)).thenReturn(DUE.plusSeconds(3600));

        service.resumeActiveResolutionCycle(ticket, resumedAt);

        assertEquals(SlaStatus.NEAR_DUE, active.getStatus());
        assertEquals(NEAR, active.getNearDueAt());
        assertEquals(DUE.plusSeconds(3600), active.getDueAt());
        verify(calculations, never()).addEffectiveSeconds(eq(resumedAt), eq(7200L), eq(policy));
    }

    @Test void breachedCycleIsNeverPaused() {
        TicketSla active = cycle(1, SlaStatus.BREACHED);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));

        service.pauseActiveResolutionCycle(ticket, NEAR);

        assertNull(active.getPausedAt());
    }

    @Test void cancellationStopsOnTimeCyclesAndCompletesBreachedCycles() {
        TicketSla firstResponse = cycle(1, SlaStatus.RUNNING);
        firstResponse.setSlaType(SlaType.FIRST_RESPONSE);
        TicketSla resolution = cycle(1, SlaStatus.BREACHED);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.FIRST_RESPONSE))
                .thenReturn(Optional.of(firstResponse));
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION))
                .thenReturn(Optional.of(resolution));

        service.terminateActiveCycles(ticket, NEAR);

        assertEquals(SlaStatus.STOPPED, firstResponse.getStatus());
        assertEquals(SlaStatus.BREACHED, resolution.getStatus());
        assertEquals(NEAR, firstResponse.getCompletedAt());
        assertEquals(NEAR, resolution.getCompletedAt());
        assertNull(firstResponse.getPausedAt());
        assertNull(resolution.getPausedAt());
    }

    @Test void duplicateLifecycleStopsOnlyResolution() {
        TicketSla resolution = cycle(1, SlaStatus.RUNNING);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(resolution));

        service.stopActiveResolutionCycleForDuplicate(ticket, NEAR);

        assertEquals(SlaStatus.STOPPED, resolution.getStatus());
        verify(slas, never()).findActiveForUpdate(ticket.getId(), SlaType.FIRST_RESPONSE);
    }

    @Test void duplicateLifecycleReconcilesAnOverdueResolutionAfterTheDuplicateLinkWasApplied() {
        TicketSla resolution = cycle(1, SlaStatus.RUNNING);
        ticket.setCurrentStatus(TicketStatus.DUPLICATE);
        ticket.setMainTicket(new Ticket());
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(resolution));
        doAnswer(invocation -> {
            resolution.setStatus(SlaStatus.BREACHED);
            return null;
        }).when(milestones).processMilestones(ticket, resolution, DUE);

        service.stopActiveResolutionCycleForDuplicate(ticket, DUE);

        assertEquals(SlaStatus.BREACHED, resolution.getStatus());
        assertEquals(DUE, resolution.getCompletedAt());
        assertNull(resolution.getPausedAt());
    }

    @Test void multipleContinuousPausesReuseOneCycleAndAccumulateEffectiveTime() {
        SlaPolicy continuous = new SlaPolicy();
        continuous.setPriority(Priority.CRITICAL);
        continuous.setSlaType(SlaType.RESOLUTION);
        continuous.setMode(SlaMode.CONTINUOUS_24X7);
        continuous.setDeadlineRule(SlaDeadlineRule.HOURS);
        continuous.setDurationSeconds(36_000L);
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        active.setPolicy(continuous);
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        TicketSlaService realCalendarService = new TicketSlaService(slas, tickets,
                new SlaCalculationService(mock(SlaPolicyRepository.class)), milestones);
        Instant firstPause = START.plusSeconds(600);
        Instant firstResume = firstPause.plusSeconds(3600);

        realCalendarService.pauseActiveResolutionCycle(ticket, firstPause);
        realCalendarService.resumeActiveResolutionCycle(ticket, firstResume);
        Instant nearAfterFirstPause = NEAR.plusSeconds(3600);
        Instant dueAfterFirstPause = DUE.plusSeconds(3600);
        assertEquals(nearAfterFirstPause, active.getNearDueAt());
        assertEquals(dueAfterFirstPause, active.getDueAt());

        Instant secondPause = firstResume.plusSeconds(600);
        Instant secondResume = secondPause.plusSeconds(7200);
        realCalendarService.pauseActiveResolutionCycle(ticket, secondPause);
        realCalendarService.resumeActiveResolutionCycle(ticket, secondResume);

        assertEquals(10_800, active.getTotalPausedSeconds());
        assertEquals(nearAfterFirstPause.plusSeconds(7200), active.getNearDueAt());
        assertEquals(dueAfterFirstPause.plusSeconds(7200), active.getDueAt());
        assertEquals(1, active.getCycleNumber());
        assertNull(active.getPausedAt());
    }

    @Test void defensiveResolutionClosesAnOpenPauseBeforeCompleting() {
        SlaPolicy continuous = new SlaPolicy();
        continuous.setPriority(Priority.CRITICAL);
        continuous.setSlaType(SlaType.RESOLUTION);
        continuous.setMode(SlaMode.CONTINUOUS_24X7);
        continuous.setDeadlineRule(SlaDeadlineRule.HOURS);
        continuous.setDurationSeconds(36_000L);
        TicketSla active = cycle(1, SlaStatus.RUNNING);
        active.setPolicy(continuous);
        active.setPausedAt(START.plusSeconds(600));
        when(slas.findActiveForUpdate(ticket.getId(), SlaType.RESOLUTION)).thenReturn(Optional.of(active));
        TicketSlaService realCalendarService = new TicketSlaService(slas, tickets,
                new SlaCalculationService(mock(SlaPolicyRepository.class)), milestones);
        Instant resolvedAt = START.plusSeconds(4200);

        realCalendarService.completeActiveResolutionCycle(ticket, resolvedAt);

        assertEquals(SlaStatus.MET, active.getStatus());
        assertEquals(resolvedAt, active.getCompletedAt());
        assertNull(active.getPausedAt());
        assertEquals(3600, active.getTotalPausedSeconds());
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
