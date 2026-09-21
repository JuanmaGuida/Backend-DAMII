package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.TicketStatus;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class InformationRequestResponse {
    private final InformationRequestStatus status;
    private final TicketStatus currentStatus;
    private final String messageForCitizen;
    private final Instant requestedAt;
    private final Instant dueAt;
    private final String responseMessage;
    private final Instant answeredAt;
    private final List<TicketAttachmentResponse> attachments;

    public InformationRequestResponse(InformationRequestStatus status, TicketStatus currentStatus,
                                      String messageForCitizen, Instant requestedAt, Instant dueAt,
                                      String responseMessage, Instant answeredAt) {
        this(status, currentStatus, messageForCitizen, requestedAt, dueAt, responseMessage, answeredAt, List.of());
    }

    public InformationRequestResponse(InformationRequestStatus status, TicketStatus currentStatus,
                                      String messageForCitizen, Instant requestedAt, Instant dueAt,
                                      String responseMessage, Instant answeredAt,
                                      List<TicketAttachmentResponse> attachments) {
        this.status = status;
        this.currentStatus = currentStatus;
        this.messageForCitizen = messageForCitizen;
        this.requestedAt = requestedAt;
        this.dueAt = dueAt;
        this.responseMessage = responseMessage;
        this.answeredAt = answeredAt;
        this.attachments = List.copyOf(attachments);
    }
}
