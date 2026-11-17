package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.MessageVisibility;

import java.time.Instant;

/**
 * Entidades V1.49 §10: proyección pública de un TicketMessage. No expone
 * ticketId (va en la URL) ni sourceModuleId (detalle de bookkeeping interno
 * entre M2 y el resto de los módulos, sin valor para el cliente del chat).
 *
 * <p>{@code authorName} sólo se completa cuando lee el personal (vista staff) y el
 * autor está registrado en M2; el ciudadano y el seguimiento público lo reciben en null.
 */
public record TicketMessageResponse(
        Long id,
        ActorType authorType,
        String authorId,
        MessageVisibility visibility,
        String text,
        Instant createdAt,
        Instant updatedAt,
        String authorName
) {
    public TicketMessageResponse(Long id, ActorType authorType, String authorId, MessageVisibility visibility,
                                 String text, Instant createdAt, Instant updatedAt) {
        this(id, authorType, authorId, visibility, text, createdAt, updatedAt, null);
    }
}
