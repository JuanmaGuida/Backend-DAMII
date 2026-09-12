package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Entity
@Table(
        name = "ticket_sla",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ticket_sla_cycle",
                columnNames = {"ticket_id", "sla_type", "cycle_number"}
        )
)
@NoArgsConstructor
public class TicketSla {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_sla_ticket"))
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "sla_type", nullable = false, length = 30)
    private SlaType slaType;

    @Column(name = "cycle_number", nullable = false)
    private Integer cycleNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_sla_policy"))
    private SlaPolicy policy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "near_due_at", nullable = false)
    private Instant nearDueAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SlaStatus status;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "total_paused_seconds", nullable = false)
    private long totalPausedSeconds;
}
