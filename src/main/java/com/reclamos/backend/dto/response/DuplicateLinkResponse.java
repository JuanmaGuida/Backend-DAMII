package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class DuplicateLinkResponse {
    private final UUID ticketId;
    private final UUID mainTicketId;
    private final TicketStatus currentStatus;
}