package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;

import java.util.UUID;

public record CreateTicketResponse(
        UUID ticketId,
        String publicId,
        String trackingCode,
        TicketStatus status,
        String generatedAnonymousAccessPassword
) {
    public CreateTicketResponse(UUID ticketId, String publicId, String trackingCode, TicketStatus status) {
        this(ticketId, publicId, trackingCode, status, null);
    }

    @Override
    public String toString() {
        return "CreateTicketResponse[ticketId=" + ticketId
                + ", publicId=" + publicId
                + ", trackingCode=<redacted>"
                + ", status=" + status
                + ", generatedAnonymousAccessPassword=<redacted>]";
    }
}
