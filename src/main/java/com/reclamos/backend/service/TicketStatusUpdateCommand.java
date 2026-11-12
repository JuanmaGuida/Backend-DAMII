package com.reclamos.backend.service;

import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import java.time.Instant;
import java.util.UUID;

/** Transport-neutral input: simulator and Core share one set of domain transitions. */
public record TicketStatusUpdateCommand(UUID ticketId, UUID eventId, String eventType, Instant occurredAt,
                                        String producerModuleId, UUID correlationId, UpdateTicketStatusRequest data) { }
