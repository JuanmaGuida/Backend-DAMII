package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.*;
import java.time.Instant;
import java.util.UUID;

public record CancellationConflictResponse(Long id, UUID ticketId, String origin,
        CancellationReasonCode reasonCode, UUID externalEventId, Instant occurredAt,
        CancellationRequestStatus status, CancellationScope cancellationScope, String decisionReason,
        String publicMessage,String internalMessage) {}
