package com.reclamos.backend.service;

import com.reclamos.backend.config.PriorityProperties;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TicketPriorityJobTest {
    private final TicketRepository tickets=mock(TicketRepository.class);
    private final TicketPriorityService worker=mock(TicketPriorityService.class);
    private final PriorityProperties properties=new PriorityProperties();
    private final Instant now=Instant.parse("2026-10-20T15:00:00Z");
    private final TicketPriorityJob job=new TicketPriorityJob(tickets,worker,properties,Clock.fixed(now,ZoneOffset.UTC));

    @Test void paginatesWithStableUpperBoundAndContinuesAfterOneFailedTransaction() {
        UUID a=new UUID(0,1), b=new UUID(0,2), c=new UUID(0,3);
        properties.setBatchSize(2);
        when(tickets.findPriorityScanUpperBound(any())).thenReturn(List.of(c));
        when(tickets.findPriorityCandidates(any(),eq(new UUID(0,0)),eq(c),any())).thenReturn(List.of(a,b));
        when(tickets.findPriorityCandidates(any(),eq(b),eq(c),any())).thenReturn(List.of(c));
        when(tickets.findPriorityCandidates(any(),eq(c),eq(c),any())).thenReturn(List.of());
        doThrow(new IllegalStateException("isolated transaction failure")).when(worker).processCandidate(a,now);
        job.recalculatePriorities();
        verify(worker).processCandidate(a,now); verify(worker).processCandidate(b,now); verify(worker).processCandidate(c,now);
        verify(tickets,times(3)).findPriorityCandidates(eq(TicketPriorityService.ACTIVE_STATUSES),any(),eq(c),
                argThat(page -> page.getPageSize()==2 && page.getOffset()==0));
        verify(tickets,never()).findAll();
    }
    @Test void emptyDatabaseDoesNotStartAnyTransaction() {
        when(tickets.findPriorityScanUpperBound(any())).thenReturn(List.of()); job.recalculatePriorities(); verifyNoInteractions(worker);
    }
}
