package com.reclamos.backend.service;

import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ResolutionConfirmationJobTest {
    @Test
    void processesOnlyCandidateIdsSelectedByRepositoryWithOneConsistentTimestamp() {
        Instant now = Instant.parse("2026-09-10T12:00:00Z");
        TicketRepository tickets = mock(TicketRepository.class);
        ResolutionConfirmationTimeoutService timeoutService = mock(ResolutionConfirmationTimeoutService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(tickets.findExpiredResolutionConfirmationIds(now)).thenReturn(List.of(first, second));
        ResolutionConfirmationJob job = new ResolutionConfirmationJob(
                tickets, timeoutService, Clock.fixed(now, ZoneOffset.UTC));

        job.closeExpiredResolutions();

        verify(tickets).findExpiredResolutionConfirmationIds(now);
        verify(timeoutService).closeIfConfirmationExpired(first, now);
        verify(timeoutService).closeIfConfirmationExpired(second, now);
        verifyNoMoreInteractions(timeoutService);
    }
}