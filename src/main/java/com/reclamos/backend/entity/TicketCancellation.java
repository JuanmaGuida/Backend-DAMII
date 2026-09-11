package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@NoArgsConstructor
@Table(name = "ticket_cancellations", uniqueConstraints =
@UniqueConstraint(name = "uk_ticket_cancellation_ticket", columnNames = "ticket_id"))
public class TicketCancellation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_cancellation_ticket"))
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private CancellationReasonCode reasonCode;

    @Column(name = "public_message", columnDefinition = "TEXT")
    private String publicMessage;
    @Column(name = "internal_message", columnDefinition = "TEXT")
    private String internalMessage;
    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by_type", nullable = false, length = 30)
    private ActorType cancelledByType;
    @Column(name = "cancelled_by_id", length = 100)
    private String cancelledById;
    @Column(name = "cancelled_by_module_id", length = 20)
    private String cancelledByModuleId;
    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;
}