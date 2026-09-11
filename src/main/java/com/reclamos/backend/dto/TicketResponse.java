package com.reclamos.backend.dto;

import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
public class TicketResponse {
    private UUID id;
    private String publicId;
    private String requestTypeCode;
    private String requestTypeName;
    private String categoryName;
    private String subcategoryName;
    private TicketType ticketType;
    private String summary;
    private TicketStatus currentStatus;
    private Priority currentPriority;
    private String responsibleAreaId;
    private String assignedAgentId;
    private boolean anonymous;
    private int estimatedAffectedCount;
    private boolean escalated;
    private UUID neighborhoodId;
    private String neighborhoodName;
    private Instant classificationFinalizedAt;
    private Instant statusChangedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
