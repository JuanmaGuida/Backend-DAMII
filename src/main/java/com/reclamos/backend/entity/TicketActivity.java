package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Entity
@Table(
        name = "ticket_activities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ticket_activity_sequence",
                        columnNames = {
                                "ticket_id",
                                "sequence"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_ticket_activity_ticket_sequence",
                        columnList = "ticket_id,sequence"
                )
        }
)
@NoArgsConstructor
public class TicketActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ticket_activity_ticket"
            )
    )
    private Ticket ticket;

    @Column(nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "action_type",
            nullable = false,
            length = 50
    )
    private ActivityType actionType;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "previous_status",
            length = 30
    )
    private TicketStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "new_status",
            length = 30
    )
    private TicketStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "actor_type",
            nullable = false,
            length = 30
    )
    private ActorType actorType;

    @Column(
            name = "actor_id",
            length = 100
    )
    private String actorId;

    @Column(
            name = "source_module_id",
            length = 20
    )
    private String sourceModuleId;

    @Column(
            name = "external_event_id"
    )
    private UUID externalEventId;

    @Column(
            name = "reason_code",
            length = 100
    )
    private String reasonCode;

    @Column(columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb"
    )
    private Map<String, Object> metadata;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "ticket_version")
    private Integer ticketVersion;
}
