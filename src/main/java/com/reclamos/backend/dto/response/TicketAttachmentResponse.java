package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.MessageVisibility;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class TicketAttachmentResponse {
    private final Long id;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final MessageVisibility visibility;
    private final Instant createdAt;
}
