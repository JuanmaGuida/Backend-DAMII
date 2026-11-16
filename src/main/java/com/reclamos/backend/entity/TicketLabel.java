package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_labels")
@Data
@NoArgsConstructor
public class TicketLabel {
    @EmbeddedId private TicketLabelId id;
    @MapsId("ticketId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", foreignKey = @ForeignKey(name = "fk_ticket_label_ticket"))
    private Ticket ticket;
    @MapsId("labelId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "label_id", foreignKey = @ForeignKey(name = "fk_ticket_label_label"))
    private Label label;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private LabelSource source;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}