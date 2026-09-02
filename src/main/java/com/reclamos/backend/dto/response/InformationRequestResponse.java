package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
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
}