package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.MessageVisibility;

import java.time.Instant;

/**
 * Entidades V1.49 §10: proyección pública de un TicketMessage. No expone
 * ticketId (va en la URL) ni sourceModuleId (detalle de bookkeeping interno
 * entre M2 y el resto de los módulos, sin valor para el cliente del chat).
 */
public record TicketMessageResponse(
        Long id,
        ActorType authorType,
        String authorId,
        MessageVisibility visibility,
        String text,
        Instant createdAt
) {
}
