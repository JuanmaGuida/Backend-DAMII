package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.EscalationReasonCode;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proyección de detalle. Se mantiene plana y separada de TicketResponse para
 * no cargar listados ni respuestas operativas con adjuntos e historial.
 */
@Data
public class TicketDetailResponse {
    private UUID id;
    private String publicId;
    private String requestTypeCode;
    private String requestTypeName;
    private String categoryName;
    private String subcategoryName;
    private TicketType ticketType;
    private String summary;
    private String description;
    private TicketStatus currentStatus;
    private Priority currentPriority;
    private String responsibleAreaId;
    private String assignedAgentId;
    private boolean anonymous;
    private int estimatedAffectedCount;
    private boolean escalated;
    private EscalationReasonCode escalationReasonCode;
    private Instant escalatedAt;
    private Instant firstResponseDueAt;
    private boolean firstResponseNearDue;
    private boolean firstResponseBreached;
    private Instant resolutionDueAt;
    private boolean slaNearDue;
    private boolean slaBreached;
    private Instant resolutionNearDueAt;
    private String neighborhoodName;
    private Instant classificationFinalizedAt;
    private Instant statusChangedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private List<TicketAttachmentResponse> attachments = List.of();
    private List<TicketActivityResponse> ticketActivities = List.of();
    private List<LabelSummaryResponse> labels = List.of();
    private PendingInformationRequestResponse pendingInformationRequest;
}
