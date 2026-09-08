package com.reclamos.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope común de updateTicketStatus (Eventos v1.6 §4), usado por el
 * simulador interno (DDA2-61) y por {@code TicketStatusUpdateService}
 * (DDA2-62) para validar el hecho ANTES de tocar el Ticket: specVersion,
 * eventId, eventType y subject se validan estructural y semánticamente
 * (ver {@code TicketStatusUpdateService.validateEnvelope}), y eventId
 * habilita la deduplicación vía {@code InboxEvent} (Entidades v1.3 §19.1).
 * <p>
 * Reemplaza al DTO plano anterior, que QA marcó como incompleto: no exponía
 * eventId/eventType/subject/producer y por lo tanto no podía validarlos ni
 * deduplicar reintentos.
 */
public record UpdateTicketStatusEnvelope(
        @NotBlank(message = "specVersion es obligatorio") String specVersion,
        @NotNull(message = "eventId es obligatorio") UUID eventId,
        @NotBlank(message = "eventType es obligatorio") String eventType,
        @NotNull(message = "occurredAt es obligatorio") Instant occurredAt,
        @NotNull(message = "producer es obligatorio") @Valid Producer producer,
        @NotBlank(message = "subject es obligatorio") String subject,
        @NotNull(message = "data es obligatorio") @Valid UpdateTicketStatusRequest data
) {
    public record Producer(
            @NotBlank(message = "producer.moduleId es obligatorio") String moduleId,
            @NotBlank(message = "producer.service es obligatorio") String service
    ) {
    }
}
