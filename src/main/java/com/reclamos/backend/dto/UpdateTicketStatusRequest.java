package com.reclamos.backend.dto;

import com.reclamos.backend.entity.UpdateTicketStatusType;
import com.reclamos.backend.entity.ResolutionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload de negocio ({@code data}) del evento updateTicketStatus (Eventos
 * v1.6 §8.1), usado tanto por el simulador interno (Story 3.4 / DDA2-61)
 * como por {@code TicketStatusUpdateService} (DDA2-62), que aplica la
 * transición real. Viaja envuelto en {@link UpdateTicketStatusEnvelope}, que
 * es donde vive el envelope común (specVersion/eventId/eventType/producer/
 * subject) — por eso este record ya no tiene un campo producerModuleId
 * propio: el productor del hecho es {@code envelope.producer().moduleId()}.
 * <p>
 * {@code ticketId} es un campo propio de {@code data} (Eventos §8.1,
 * "Ticket de M2 sobre el que se informa el hecho"), separado de
 * {@code envelope.subject}. {@code TicketStatusUpdateService} valida que
 * coincida con el ticket de la URL antes de tocar nada.
 * <p>
 * Simplificación consciente que sigue vigente: no incluye
 * {@code attachments} (el módulo todavía no persiste adjuntos, Sprint 5 /
 * Story 8.1).
 */
public record UpdateTicketStatusRequest(
        @NotNull(message = "ticketId es obligatorio") UUID ticketId,
        @NotNull(message = "updateType es obligatorio") UpdateTicketStatusType updateType,
        String publicMessage,
        String internalMessage,
        @Min(value = 0, message = "progress debe estar entre 0 y 100")
        @Max(value = 100, message = "progress debe estar entre 0 y 100") Integer progress,
        @Valid Details details,
        @NotNull(message = "updatedBy es obligatorio") @Valid Actor updatedBy,
        @NotNull(message = "updateOccurredAt es obligatorio") Instant updateOccurredAt
) implements StrictEventPayload {
    public record Details(
            InformationRequest informationRequest,
            ReturnInfo returnInfo,
            Resolution resolution,
            Cancellation cancellation
    ) implements StrictEventPayload {
    }

    public record InformationRequest(String messageForCitizen, Instant requiredBy) implements StrictEventPayload {
    }

    public record ReturnInfo(String reasonCode) implements StrictEventPayload {
    }

    public record Resolution(ResolutionType type) implements StrictEventPayload {
    }

    public record Cancellation(String reasonCode) implements StrictEventPayload {
    }

    /**
     * type es CITIZEN│AGENT│AREA_RESPONSIBLE│ADMIN│EXTERNAL_USER│SYSTEM per
     * Eventos V1.69 §5.2 — el mismo enum ActorType completo (ver su
     * javadoc). Para updateTicketStatus, que siempre llega desde otro
     * módulo vía integración, el valor esperado en la práctica es
     * EXTERNAL_USER (ver ejemplos §8.2/§8.3 de Eventos V1.69). Se mapea a
     * nuestro ActorType interno en el service — ver el comentario ahí.
     * <p>
     * {@code id} es un UUID o ausente (Eventos §5.2): un identificador con
     * otro formato se rechaza en lugar de guardarse como identidad opaca.
     */
    public record Actor(
            @NotNull(message = "updatedBy.type es obligatorio") String type,
            @Pattern(regexp = UUID_PATTERN, message = "updatedBy.id debe ser un UUID o null") String id
    ) implements StrictEventPayload {
        private static final String UUID_PATTERN =
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    }
}
