package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.InformationRequestPurpose;

import java.time.Instant;
import java.util.List;

public record PendingInformationRequestResponse(
        InformationRequestStatus status,
        String messageForCitizen,
        Instant requestedAt,
        Instant dueAt,
        List<TicketAttachmentResponse> attachments,
        InformationRequestPurpose purpose,
        ReclassificationRequirementsResponse reclassification
) {
    public PendingInformationRequestResponse(InformationRequestStatus status, String messageForCitizen,
            Instant requestedAt, Instant dueAt, List<TicketAttachmentResponse> attachments) {
        this(status, messageForCitizen, requestedAt, dueAt, attachments, InformationRequestPurpose.GENERAL, null);
    }
}
