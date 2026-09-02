package com.reclamos.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox transaccional (Entidades v1.3 §19.2). Se inserta en la MISMA
 * transacción que el cambio de negocio; todavía no existe un publisher
 * asíncrono real (Sprint 3: "sin consumidor real todavía"), así que los
 * eventos quedan en PENDING hasta que se implemente ese publisher.
 */
@Entity
@Table(
        name = "outbox_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_outbox_event_event_id", columnNames = "event_id")
        },
        indexes = {
                @Index(name = "idx_outbox_event_status_created", columnList = "status,created_at")
        }
)
@Data
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "update_type", length = 30)
    private TicketUpdatedType updateType;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_outbox_event_ticket")
    )
    private Ticket ticket;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
