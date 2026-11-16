package com.reclamos.backend.ai;

import com.reclamos.backend.entity.TicketStatus;

import java.util.List;
import java.util.UUID;

/**
 * Ayuda para quien revisa un ticket. Todo es orientativo: la prioridad real la calcula el sistema
 * y vincular un duplicado sigue siendo una acción explícita del agente.
 *
 * @param source  {@code AI} si respondió el modelo; {@code UNAVAILABLE} si sólo se devuelven los
 *                candidatos por cercanía, sin resumen
 * @param urgencyHint {@code LOW}, {@code MEDIUM}, {@code HIGH} o {@code CRITICAL}; nulo sin IA
 */
public record AiTicketInsightsResponse(
        String source,
        String summary,
        String urgencyHint,
        String urgencyReason,
        List<DuplicateHint> duplicates
) {
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_UNAVAILABLE = "UNAVAILABLE";

    /**
     * @param similarity {@code HIGH}, {@code MEDIUM} o {@code LOW} según la IA; nulo si el candidato
     *                   sólo está listado por cercanía
     */
    public record DuplicateHint(
            UUID ticketId,
            String publicId,
            String summary,
            String requestTypeName,
            TicketStatus currentStatus,
            double distanceMeters,
            String similarity,
            String reason
    ) {
    }
}
