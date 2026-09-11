package com.reclamos.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Inbox transaccional (Entidades v1.3 §19.1). Garantiza idempotencia de
 * eventos consumidos: eventId es la PK, así que un intento concurrente de
 * procesar el mismo eventId dos veces choca contra la restricción única de
 * la base al hacer commit, además del chequeo aplicativo en
 * {@code TicketStatusUpdateService}.
 */
@Entity
@Table(name = "inbox_events")
@Data
@NoArgsConstructor
public class InboxEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "producer_module_id", nullable = false, length = 20)
    private String producerModuleId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboxStatus status = InboxStatus.RECEIVED;

    @Column(columnDefinition = "text")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;
}
