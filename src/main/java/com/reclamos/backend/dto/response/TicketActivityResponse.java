package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class TicketActivityResponse {
    private final Integer sequence;
    private final ActivityType actionType;
    private final TicketStatus previousStatus;
    private final TicketStatus newStatus;
    private final Instant occurredAt;
    private final String reasonCode;
    private final ActorType actorType;
    private final Priority previousPriority;
    private final Priority newPriority;
    private final String message;
}
