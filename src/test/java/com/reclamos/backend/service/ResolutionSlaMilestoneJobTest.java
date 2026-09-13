package com.reclamos.backend.service;

import com.reclamos.backend.repository.TicketSlaCandidate;
import com.reclamos.backend.repository.TicketSlaRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ResolutionSlaMilestoneJobTest {
    @Test void delegatesEachCandidateWithOneConsistentTimestamp() {
        Instant now = Instant.parse("2026-09-10T12:10:00Z");
        TicketSlaRepository slas = mock(TicketSlaRepository.class);
        TicketSlaMilestoneService processor = mock(TicketSlaMilestoneService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(slas.findPendingMilestones(now)).thenReturn(List.of(
                new TicketSlaCandidate(first, 11L), new TicketSlaCandidate(second, 12L)));

        new TicketSlaMilestoneJob(slas, processor, Clock.fixed(now, ZoneOffset.UTC))
                .processPendingMilestones();

        verify(processor).processCandidate(first, 11L, now);
        verify(processor).processCandidate(second, 12L, now);
    }
}
