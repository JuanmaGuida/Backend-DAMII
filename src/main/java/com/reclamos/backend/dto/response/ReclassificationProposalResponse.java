package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Administrative projection; never used in citizen views or integration events. */
public record ReclassificationProposalResponse(
        Long id, UUID ticketId, ReclassificationOrigin origin, ReclassificationProposalStatus status,
        Long requestTypeId, Long formTemplateId, Map<String, Object> proposedData,
        Map<String, Object> previousClassificationSnapshot, UUID informationRequestId,
        String proposedBy, String confirmedBy, String abandonmentReason,
        Instant createdAt, Instant updatedAt, Instant resolvedAt,
        ReclassificationRequirementsResponse requirements) { }
