package com.reclamos.backend.service;

import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.repository.IndicatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IndicatorService {
    private final IndicatorRepository repository;

    public List<CategoryIndicatorResponse> categories(IndicatorFilter filter) {
        return repository.aggregateCategories(filter);
    }

    public List<NeighborhoodIndicatorResponse> neighborhoods(IndicatorFilter filter) {
        return repository.aggregateNeighborhoods(filter);
    }

    public List<PriorityIndicatorResponse> priorities(IndicatorFilter filter) {
        return repository.aggregatePriorities(filter).stream()
                .sorted(Comparator.comparingInt(value -> value.getPriority().ordinal())).toList();
    }

    public List<AreaIndicatorResponse> areas(IndicatorFilter filter) {
        return repository.aggregateAreas(filter);
    }

    public List<SlaIndicatorResponse> sla(IndicatorFilter filter) {
        return repository.aggregateSla(filter).stream()
                .sorted(Comparator.comparingInt((SlaIndicatorResponse value) -> value.getSlaType().ordinal())
                        .thenComparingInt(value -> value.getStatus().ordinal()))
                .toList();
    }
}