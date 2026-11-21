package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Metadata owned by an external event, optionally linked to an internal local copy. Do not log URLs. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "external_attachment_references")
public class ExternalAttachmentReference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;
    private UUID externalEventId;
    private int ordinal;
    @Enumerated(EnumType.STRING)
    private UpdateTicketStatusType updateType;
    private String sourceModuleId;
    private Instant occurredAt;
    @Column(columnDefinition = "text") private String fileName;
    @Column(columnDefinition = "text") private String contentType;
    @Column(columnDefinition = "text") private String url;
    private Long sizeBytes;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_attachment_id")
    private Attachment localAttachment;
    @Enumerated(EnumType.STRING)
    private MessageVisibility visibility;
}
