package com.reclamos.backend.dto;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;

import java.util.UUID;

/**
 * Filtros combinables para la bandeja de tickets (Story 3.1 - BE: Endpoint de
 * listado con filtros por categoría, prioridad, rol y estado).
 * <p>
 * El work item pide filtrar por "rol", pero no existe ningún campo "rol" en
 * el modelo de datos (Entidades v1.3). Se interpretó como {@code responsibleAreaId}
 * por ser lo más cercano semánticamente (el área responsable a la que está
 * asignado el ticket). Si el equipo quiso decir otra cosa (por ejemplo, scoping
 * por el rol del usuario que consulta), este filtro hay que revisarlo.
 */
public record TicketFilter(
        Long categoryId,
        Priority priority,
        UUID neighborhoodId,
        String responsibleAreaId,
        TicketStatus status
) {
}
