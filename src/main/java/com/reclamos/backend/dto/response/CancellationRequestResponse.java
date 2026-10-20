package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.*;
import java.time.Instant;
import java.util.UUID;

public record CancellationRequestResponse(UUID id, UUID ticketId, UUID requestedByCitizenId,
        CancellationRequesterKind requesterKind, String reason, CancellationReasonCode reasonCode,
        CancellationRequestStatus status, Instant requestedAt, Instant reviewedAt, UUID reviewedBy,
        CancellationScope cancellationScope, String decisionReason) {}
