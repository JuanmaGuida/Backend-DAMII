package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "tickets",
        indexes = {
                @Index(
                        name = "idx_ticket_citizen_status",
                        columnList = "citizen_id,current_status"
                ),
                @Index(
                        name = "idx_ticket_area_status",
                        columnList = "responsible_area_id,current_status"
                ),
                @Index(
                        name = "idx_ticket_request_type",
                        columnList = "request_type_id"
                ),
                @Index(
                        name = "idx_ticket_main",
                        columnList = "main_ticket_id"
                )
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ticket_public_id",
                        columnNames = "public_id"
                )
        }
)
@Check(
        constraints = """
        (is_anonymous = true AND citizen_id IS NULL)
        OR
        (is_anonymous = false AND citizen_id IS NOT NULL)
        """
)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "public_id", nullable = false, length = 50)
    private String publicId;

    @Column(name = "tracking_code_hash", nullable = false, length = 255)
    private String trackingCodeHash;

    @Column(name = "citizen_id")
    private Long citizenId;

    @Column(name = "is_anonymous", nullable = false)
    private boolean anonymous;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "request_type_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_request_type")
    )
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 30)
    private TicketType ticketType;

    @Column(name = "responsible_area_id", nullable = false, length = 100)
    private String responsibleAreaId;

    @Column(name = "assigned_agent_id", length = 100)
    private String assignedAgentId;

    @Column(nullable = false, length = 200)
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> formData = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 30)
    private TicketStatus currentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "initial_priority", nullable = false, length = 20)
    private PriorityFactor initialPriorityFactor;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_priority", nullable = false, length = 20)
    private PriorityFactor currentPriorityFactor;

    @Column(name = "affected_count", nullable = false)
    private int affectedCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "main_ticket_id",
            foreignKey = @ForeignKey(name = "fk_ticket_main_ticket")
    )
    private Ticket mainTicket;

    @Column(name = "is_escalated", nullable = false)
    private boolean escalated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "escalation_reason_code", length = 30)
    private EscalationReasonCode escalationReasonCode;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount = 0;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "resolution_confirmation_due_at")
    private Instant resolutionConfirmationDueAt;

    @Column(name = "preferred_notification_channel", length = 30)
    private String preferredNotificationChannel;

    @Column(name = "ticket_version", nullable = false)
    private int ticketVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
