package com.reclamos.backend.service;

import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.IndicatorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorServiceTest {
    @Mock IndicatorRepository repository;
    @InjectMocks IndicatorService service;

    private final IndicatorFilter filter = new IndicatorFilter(1L, null, Priority.HIGH, "M6",
            TicketStatus.IN_PROGRESS, SlaType.RESOLUTION, SlaStatus.BREACHED);

    @Test
    void delegatesSharedFilterAndPreservesEmptyResults() {
        when(repository.aggregateCategories(filter)).thenReturn(List.of());
        assertEquals(List.of(), service.categories(filter));
        verify(repository).aggregateCategories(filter);
    }

    @Test
    void delegatesEveryAggregationAndAppliesSemanticOrdering() {
        when(repository.aggregateNeighborhoods(filter)).thenReturn(List.of(
                new NeighborhoodIndicatorResponse(null, "Centro", 2)));
        when(repository.aggregatePriorities(filter)).thenReturn(List.of(
                new PriorityIndicatorResponse(Priority.CRITICAL, 1),
                new PriorityIndicatorResponse(Priority.LOW, 2)));
        when(repository.aggregateAreas(filter)).thenReturn(List.of(new AreaIndicatorResponse("M6", 3)));
        when(repository.aggregateSla(filter)).thenReturn(List.of(
                new SlaIndicatorResponse(SlaType.RESOLUTION, SlaStatus.RUNNING, 1),
                new SlaIndicatorResponse(SlaType.FIRST_RESPONSE, SlaStatus.MET, 2)));

        assertEquals("Centro", service.neighborhoods(filter).getFirst().getNeighborhoodName());
        assertEquals(Priority.LOW, service.priorities(filter).getFirst().getPriority());
        assertEquals("M6", service.areas(filter).getFirst().getResponsibleAreaId());
        assertEquals(SlaType.FIRST_RESPONSE, service.sla(filter).getFirst().getSlaType());
        verify(repository).aggregateNeighborhoods(filter);
        verify(repository).aggregatePriorities(filter);
        verify(repository).aggregateAreas(filter);
        verify(repository).aggregateSla(filter);
    }
}