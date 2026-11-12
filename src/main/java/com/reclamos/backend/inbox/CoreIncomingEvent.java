package com.reclamos.backend.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record CoreIncomingEvent(UUID eventId, String eventType, String sourceModule, Instant occurredAt,
                                UUID correlationId, UUID causationId, JsonNode data, String hash) {
    @Override public String toString() { return "CoreIncomingEvent[eventId=" + eventId + ", payload=REDACTED]"; }
}
