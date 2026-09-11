package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@NoArgsConstructor
@Table(name = "ticket_resolutions", indexes = {
        @Index(name = "idx_ticket_resolution_ticket_resolved_at", columnList = "ticket_id,resolved_at")
})
public class TicketResolution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_resolution_ticket"))
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", nullable = false, length = 50)
    private ResolutionType type;

    @Column(name = "public_message", nullable = false, columnDefinition = "TEXT")
    private String publicMessage;

    @Column(name = "internal_message", columnDefinition = "TEXT")
    private String internalMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_by_type", nullable = false, length = 30)
    private ActorType resolvedByType;

    @Column(name = "resolved_by_id", length = 100)
    private String resolvedById;

    @Column(name = "resolved_by_module_id", nullable = false, length = 20)
    private String resolvedByModuleId;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;
}
