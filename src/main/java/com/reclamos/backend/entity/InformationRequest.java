package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@NoArgsConstructor
@Table(name = "information_requests", indexes = {
        @Index(name = "idx_information_request_ticket", columnList = "ticket_id"),
        @Index(name = "idx_information_request_pending_due", columnList = "status,due_at")
})
public class InformationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_information_request_ticket"))
    private Ticket ticket;

    @Column(name = "requested_by_module_id", length = 20)
    private String requestedByModuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_by_actor_type", nullable = false, length = 30)
    private ActorType requestedByActorType;

    @Column(name = "requested_by_actor_id", length = 100)
    private String requestedByActorId;

    @Column(name = "message_for_citizen", nullable = false, columnDefinition = "TEXT")
    private String messageForCitizen;

    @Column(name = "internal_message", columnDefinition = "TEXT")
    private String internalMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_status", nullable = false, length = 30)
    private TicketStatus resumeStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InformationRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "response_message", columnDefinition = "TEXT")
    private String responseMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "answered_by_type", length = 30)
    private ActorType answeredByType;

    @Column(name = "answered_by_id", length = 100)
    private String answeredById;

    @Column(name = "answered_at")
    private Instant answeredAt;
}