package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "ticket_reclassification_proposals")
@Getter @Setter
public class TicketReclassificationProposal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30)
    private ReclassificationOrigin origin;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ReclassificationProposalStatus status;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_request_type_id", nullable = false, updatable = false)
    private RequestType targetRequestType;
    @Column(name = "target_form_template_id", updatable = false)
    private Long targetFormTemplateId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "proposed_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> proposedData;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_classification_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> previousClassificationSnapshot;
    @Column(name = "information_request_id")
    private UUID informationRequestId;
    @Column(name = "authorizing_return_event_id", updatable = false)
    private UUID authorizingReturnEventId;
    @Column(name = "proposed_by", nullable = false, updatable = false, length = 100)
    private String proposedBy;
    @Column(name = "confirmed_by", length = 100)
    private String confirmedBy;
    @Column(name = "abandonment_reason", columnDefinition = "text")
    private String abandonmentReason;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
