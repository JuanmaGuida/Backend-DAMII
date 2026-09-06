package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Data
@Entity
@NoArgsConstructor
@Table(
        name = "attachments",
        indexes = {
                @Index(
                        name = "idx_attachment_ticket_created",
                        columnList = "ticket_id,created_at"
                )
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_attachment_storage_key",
                        columnNames = "storage_key"
                )
        }
)
public class Attachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_attachment_ticket")
    )
    private Ticket ticket;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "uploaded_by_type", nullable = false, length = 30)
    private ActorType uploadedByType;

    @Column(name = "uploaded_by_id", length = 100)
    private String uploadedById;

    @Column(name = "source_module_id", length = 20)
    private String sourceModuleId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
