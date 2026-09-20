package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.InformationRequestStatus;

import java.time.Instant;
import java.util.List;

public record PendingInformationRequestResponse(
        InformationRequestStatus status,
        String messageForCitizen,
        Instant requestedAt,
        Instant dueAt,
        List<TicketAttachmentResponse> attachments
) {
}
