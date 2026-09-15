package com.reclamos.backend.lab.messaging;

public record LabP2pMessage(
        String ticketId,
        String publicId,
        String requestType,
        String status,
        int sequence
) {
}
