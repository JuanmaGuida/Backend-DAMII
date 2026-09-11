package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class TicketResolutionActionResponse {
    private final UUID ticketId;
    private final TicketStatus status;
    private final Instant statusChangedAt;
    private final int reopenCount;
}