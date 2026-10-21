package com.reclamos.backend.dto;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import java.util.Set;
import java.util.UUID;

/** Filters shared by every aggregation in an overview. Dates use the server clock. */
public record IndicatorOverviewFilter(Long categoryId, UUID neighborhoodId, Priority priority,
        String responsibleAreaId, TicketStatus status, Boolean withoutLocation, String sla, Integer days) {
    public void validate() {
        if (days != null && !Set.of(7, 30, 90, 365).contains(days)) {
            throw new InvalidTicketRequestException("days debe ser 7, 30, 90 o 365");
        }
        if (sla != null && !Set.of("", "breached", "near_due", "on_track", "no_resolution_sla").contains(sla)) {
            throw new InvalidTicketRequestException("Filtro SLA inválido");
        }
    }
}
