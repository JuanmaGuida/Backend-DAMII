package com.reclamos.backend.service;

import com.reclamos.backend.dto.UpdateTicketStatusEnvelope;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import java.util.UUID;

/** The legacy DEV envelope stays at the simulator boundary, never on the Core wire. */
final class SimulatorStatusUpdateAdapter {
    static TicketStatusUpdateCommand adapt(UUID ticketId, UpdateTicketStatusEnvelope envelope) {
        if (!"1.0".equals(envelope.specVersion())) throw new InvalidTicketRequestException(
                "specVersion no soportado: " + envelope.specVersion() + " (se espera 1.0)");
        if (!"updateTicketStatus".equals(envelope.eventType())) throw new InvalidTicketRequestException(
                "eventType inválido: se esperaba 'updateTicketStatus'");
        if (!("tickets/" + ticketId).equals(envelope.subject())) throw new InvalidTicketRequestException(
                "subject ('" + envelope.subject() + "') no coincide con el ticket de la URL ('tickets/" + ticketId + "')");
        return new TicketStatusUpdateCommand(ticketId, envelope.eventId(), envelope.eventType(), envelope.occurredAt(),
                envelope.producer().moduleId(), null, envelope.data());
    }
    private SimulatorStatusUpdateAdapter() { }
}
