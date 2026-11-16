package com.reclamos.backend.repository;

import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.*;

import java.util.List;

public interface IndicatorRepository {
    List<CategoryIndicatorResponse> aggregateCategories(IndicatorFilter filter);
    List<NeighborhoodIndicatorResponse> aggregateNeighborhoods(IndicatorFilter filter);
    List<PriorityIndicatorResponse> aggregatePriorities(IndicatorFilter filter);
    List<AreaIndicatorResponse> aggregateAreas(IndicatorFilter filter);
    List<SlaIndicatorResponse> aggregateSla(IndicatorFilter filter);
}