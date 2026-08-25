package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Data
@Entity
@NoArgsConstructor
@Table(name = "ticket_messages",
        indexes = {
                @Index(
                        name = "idx_ticket_message_ticket_created",
                        columnList = "ticket_id,created_at"
                )
        })
public class TicketMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ticket_message_ticket"
            )
    )
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "author_type",
            nullable = false,
            length = 30
    )
    private ActorType authorType;

    @Column(
            name = "author_id",
            length = 100
    )
    private String authorId;

    @Column(
            name = "source_module_id",
            length = 20
    )
    private String sourceModuleId;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20
    )
    private MessageVisibility visibility;

    @Column(
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String text;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;
}
