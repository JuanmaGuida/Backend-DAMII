package com.reclamos.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
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
                ),
                @UniqueConstraint(name = "uk_ticket_tracking_code_hash", columnNames = "tracking_code_hash")
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
    @Generated(event = {EventType.INSERT, EventType.UPDATE}, writable = true)
    @Column(name = "resource_version", nullable = false)
    private long resourceVersion;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "public_id", nullable = false, length = 50)
    private String publicId;

    @Column(name = "tracking_code_hash", nullable = false, length = 255)
    @ToString.Exclude
    private String trackingCodeHash;

    @Column(name = "tracking_code_ciphertext", columnDefinition = "bytea")
    @ToString.Exclude
    @JsonIgnore
    private byte[] trackingCodeCiphertext;

    @Column(name = "tracking_code_nonce", columnDefinition = "bytea")
    @ToString.Exclude
    @JsonIgnore
    private byte[] trackingCodeNonce;

    @Column(name = "tracking_code_key_id", length = 64)
    @ToString.Exclude
    @JsonIgnore
    private String trackingCodeKeyId;

    @Column(name = "anonymous_access_password_hash", length = 255)
    @ToString.Exclude
    private String anonymousAccessPasswordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "anonymous_contact_channel", length = 20)
    @ToString.Exclude
    private AnonymousContactChannel anonymousContactChannel;

    @Column(name = "anonymous_contact_value", length = 254)
    @ToString.Exclude
    private String anonymousContactValue;

    @Column(name = "citizen_id")
    private UUID citizenId;

    @Column(name = "is_anonymous", nullable = false)
    private boolean anonymous;

    @Column(name = "core_origin_verified", nullable = false, updatable = false)
    private boolean coreOriginVerified;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "request_type_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_request_type")
    )
    private RequestType requestType;

    /**
     * Entidades V1.49 §4/§6: registra CON QUÉ FormTemplate/versión quedó
     * asociado formData, ya que FormValidationService resuelve "la plantilla
     * activa" en vivo a partir de requestTypeId y esa plantilla puede
     * cambiar de versión con el tiempo. Se fija en create() y se recalcula
     * en correctClassification() junto con requestType y formData; queda
     * congelado en cuanto classificationFinalizedAt se setea. Nullable
     * porque un RequestType puede no tener formulario configurado (formData
     * vacío) y porque los tickets creados antes de esta columna no tienen
     * valor.
     */
    @Column(name = "form_template_id")
    private Long formTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 30)
    private TicketType ticketType;

    @Column(name = "responsible_area_id", nullable = false, length = 100)
    private String responsibleAreaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_agent_id",
            foreignKey = @ForeignKey(name = "fk_ticket_assigned_agent"))
    private ModuleUser assignedAgent;

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
    @Column(name = "current_priority", nullable = false, length = 20)
    private Priority currentPriority;

    @Column(name = "estimated_affected_count", nullable = false)
    private int estimatedAffectedCount = 0;

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

    @Column(name = "external_update_at")
    private Instant externalUpdateAt;

    @Column(name = "resolution_confirmation_due_at")
    private Instant resolutionConfirmationDueAt;

    @Column(name = "preferred_notification_channel", length = 30)
    private String preferredNotificationChannel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "classification_finalized_at")
    private Instant classificationFinalizedAt;

    @Column(name = "current_progress")
    private Short currentProgress;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /** Query without mutation, also used by Inbox before choosing its disposition. */
    public boolean canPerform(TicketAction action) {
        return TicketState.of(currentStatus).next(this, action, currentStatus) != null;
    }

    public TicketStatus nextStatus(TicketAction action) {
        return nextStatus(action, null);
    }

    private TicketStatus nextStatus(TicketAction action, TicketStatus resumeStatus) {
        TicketStatus next = TicketState.of(currentStatus).next(this, action, resumeStatus);
        if (next == null) {
            throw new com.reclamos.backend.exception.TicketStateConflictException(
                    action + " no es válido en " + currentStatus);
        }
        return next;
    }

    public void transition(TicketAction action, Instant occurredAt) {
        transition(action, occurredAt, null);
    }

    /** Rejected operations cannot mutate status or its timestamp. */
    public void transition(TicketAction action, Instant occurredAt, TicketStatus resumeStatus) {
        TicketStatus next = nextStatus(action, resumeStatus);
        if (next != currentStatus) {
            currentStatus = next;
            statusChangedAt = occurredAt;
        }
    }
}
