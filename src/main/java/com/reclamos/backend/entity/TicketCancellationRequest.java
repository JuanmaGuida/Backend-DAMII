package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity @Getter @Setter
@Table(name = "ticket_cancellation_requests")
public class TicketCancellationRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false) private Ticket ticket;
    private UUID requestedByCitizenId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CancellationRequesterKind requesterKind;
    @Column(nullable = false, columnDefinition = "TEXT") private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50)
    private CancellationReasonCode reasonCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CancellationRequestStatus status;
    @Column(nullable = false) private Instant requestedAt;
    private Instant reviewedAt;
    private UUID reviewedBy;
    @Enumerated(EnumType.STRING) @Column(length = 20) private CancellationScope cancellationScope;
    @Column(columnDefinition = "TEXT") private String decisionReason;
    // Internal eligibility snapshot: no credentials or private citizen data.
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<UUID> duplicateSnapshot;
    @Column(nullable = false) private Instant requestedStatusChangedAt;
}
