package com.reclamos.backend.controller;

import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.service.IndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/indicators")
@RequiredArgsConstructor
public class IndicatorController {
    private final IndicatorService service;

    @GetMapping("/categories")
    public List<CategoryIndicatorResponse> categories(@ModelAttribute IndicatorQuery query) {
        return service.categories(query.toFilter());
    }

    @GetMapping("/neighborhoods")
    public List<NeighborhoodIndicatorResponse> neighborhoods(@ModelAttribute IndicatorQuery query) {
        return service.neighborhoods(query.toFilter());
    }

    @GetMapping("/priorities")
    public List<PriorityIndicatorResponse> priorities(@ModelAttribute IndicatorQuery query) {
        return service.priorities(query.toFilter());
    }

    @GetMapping("/areas")
    public List<AreaIndicatorResponse> areas(@ModelAttribute IndicatorQuery query) {
        return service.areas(query.toFilter());
    }

    @GetMapping("/sla")
    public List<SlaIndicatorResponse> sla(@ModelAttribute IndicatorQuery query) {
        return service.sla(query.toFilter());
    }

    public record IndicatorQuery(Long categoryId, UUID neighborhoodId, Priority priority,
                                 String responsibleAreaId, TicketStatus status,
                                 SlaType slaType, SlaStatus slaStatus) {
        IndicatorFilter toFilter() {
            return new IndicatorFilter(categoryId, neighborhoodId, priority, responsibleAreaId,
                    status, slaType, slaStatus);
        }
    }
}