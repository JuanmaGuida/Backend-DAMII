package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;

import java.util.UUID;

public record CreateTicketResponse(UUID ticketId, String publicId, String trackingCode, TicketStatus status) {
}
