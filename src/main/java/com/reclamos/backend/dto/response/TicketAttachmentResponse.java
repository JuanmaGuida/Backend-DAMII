package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.MessageVisibility;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.Instant;

@Getter
@EqualsAndHashCode
public class TicketAttachmentResponse {

    private final Long id;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final MessageVisibility visibility;
    private final Instant createdAt;
    private final String downloadUrl;

    public TicketAttachmentResponse(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            MessageVisibility visibility,
            Instant createdAt,
            String downloadUrl) {

        this.id = id;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.visibility = visibility;
        this.createdAt = createdAt;
        this.downloadUrl = downloadUrl;
    }

    public TicketAttachmentResponse(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            MessageVisibility visibility,
            Instant createdAt) {

        this(
                id,
                fileName,
                contentType,
                sizeBytes,
                visibility,
                createdAt,
                null
        );
    }
}