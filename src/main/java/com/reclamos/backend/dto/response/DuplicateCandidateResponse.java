package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record DuplicateCandidateResponse(
        UUID ticketId,
        String publicId,
        String summary,
        TicketStatus currentStatus,
        Long categoryId,
        String categoryName,
        String requestTypeCode,
        String requestTypeName,
        UUID neighborhoodId,
        String neighborhoodName,
        double distanceMeters,
        long timeDifferenceMinutes,
        Instant createdAt
) {
}