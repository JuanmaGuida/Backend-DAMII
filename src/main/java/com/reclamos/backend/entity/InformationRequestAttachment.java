package com.reclamos.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@Table(name = "information_request_attachments",
        uniqueConstraints = @UniqueConstraint(name = "uk_information_request_attachment",
                columnNames = {"information_request_id", "attachment_id"}),
        indexes = @Index(name = "idx_information_request_attachment_request_role",
                columnList = "information_request_id,role"))
public class InformationRequestAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "information_request_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_information_request_attachment_request"))
    private InformationRequest informationRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attachment_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_information_request_attachment_attachment"))
    private Attachment attachment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InformationAttachmentRole role;
}
