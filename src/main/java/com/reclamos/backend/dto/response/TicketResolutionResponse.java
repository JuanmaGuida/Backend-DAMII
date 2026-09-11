package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class TicketResolutionResponse {
    private final UUID resolutionId;
    private final UUID ticketId;
    private final TicketStatus status;
    private final ResolutionType type;
    private final String publicMessage;
    private final String internalMessage;
    private final Instant resolvedAt;
}