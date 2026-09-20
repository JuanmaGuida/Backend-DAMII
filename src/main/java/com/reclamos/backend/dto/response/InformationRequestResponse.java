package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.TicketStatus;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
public class InformationRequestResponse {
    private final UUID informationRequestId;
    private final UUID ticketId;
    private final InformationRequestStatus status;
    private final String messageForCitizen;
    private final Instant requestedAt;
    private final Instant dueAt;
    private final TicketStatus resumeStatus;
    private final String responseMessage;
    private final Instant answeredAt;
    private final List<TicketAttachmentResponse> attachments;

    public InformationRequestResponse(UUID informationRequestId, UUID ticketId, InformationRequestStatus status,
                                      String messageForCitizen, Instant requestedAt, Instant dueAt,
                                      TicketStatus resumeStatus, String responseMessage, Instant answeredAt) {
        this(informationRequestId, ticketId, status, messageForCitizen, requestedAt, dueAt, resumeStatus,
                responseMessage, answeredAt, List.of());
    }

    public InformationRequestResponse(UUID informationRequestId, UUID ticketId, InformationRequestStatus status,
                                      String messageForCitizen, Instant requestedAt, Instant dueAt,
                                      TicketStatus resumeStatus, String responseMessage, Instant answeredAt,
                                      List<TicketAttachmentResponse> attachments) {
        this.informationRequestId = informationRequestId;
        this.ticketId = ticketId;
        this.status = status;
        this.messageForCitizen = messageForCitizen;
        this.requestedAt = requestedAt;
        this.dueAt = dueAt;
        this.resumeStatus = resumeStatus;
        this.responseMessage = responseMessage;
        this.answeredAt = answeredAt;
        this.attachments = List.copyOf(attachments);
    }
}
