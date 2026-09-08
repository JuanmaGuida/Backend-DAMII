package com.reclamos.backend.dto;

import com.reclamos.backend.entity.UpdateTicketStatusType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Payload de negocio ({@code data}) del evento updateTicketStatus (Eventos
 * v1.6 §8.1), usado tanto por el simulador interno (Story 3.4 / DDA2-61)
 * como por {@code TicketStatusUpdateService} (DDA2-62), que aplica la
 * transición real. Viaja envuelto en {@link UpdateTicketStatusEnvelope}, que
 * es donde vive el envelope común (specVersion/eventId/eventType/producer/
 * subject) — por eso este record ya no tiene un campo producerModuleId
 * propio: el productor del hecho es {@code envelope.producer().moduleId()}.
 * <p>
 * Simplificación consciente que sigue vigente: no incluye
 * {@code attachments} (el módulo todavía no persiste adjuntos, Sprint 5 /
 * Story 8.1).
 */
public record UpdateTicketStatusRequest(
        @NotNull(message = "updateType es obligatorio") UpdateTicketStatusType updateType,
        String publicMessage,
        String internalMessage,
        @Min(value = 0, message = "progress debe estar entre 0 y 100")
        @Max(value = 100, message = "progress debe estar entre 0 y 100") Integer progress,
        @Valid Details details,
        @NotNull(message = "updatedBy es obligatorio") @Valid Actor updatedBy,
        @NotNull(message = "updateOccurredAt es obligatorio") Instant updateOccurredAt
) {
    public record Details(
            InformationRequest informationRequest,
            ReturnInfo returnInfo,
            Resolution resolution,
            Cancellation cancellation
    ) {
    }

    public record InformationRequest(String messageForCitizen, Instant requiredBy) {
    }

    public record ReturnInfo(String reasonCode) {
    }

    public record Resolution(String type) {
    }

    public record Cancellation(String reasonCode) {
    }

    /**
     * type es CITIZEN│AGENT│AREA_USER│SYSTEM per Eventos v1.6 §5.2 (todavía
     * no renombrado en ese documento). Se mapea a nuestro ActorType interno
     * en el service — ver el comentario ahí.
     */
    public record Actor(@NotNull(message = "updatedBy.type es obligatorio") String type, String id) {
    }
}
