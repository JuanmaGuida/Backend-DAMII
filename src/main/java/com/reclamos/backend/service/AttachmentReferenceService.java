package com.reclamos.backend.service;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.entity.Attachment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Objects;

@Service
public class AttachmentReferenceService {
    private final String publicBaseUrl;

    public AttachmentReferenceService(AttachmentProperties properties) {
        URI configured = Objects.requireNonNull(properties.getPublicBaseUrl(),
                "attachment.public-base-url es obligatorio");
        if (!"http".equalsIgnoreCase(configured.getScheme())
                && !"https".equalsIgnoreCase(configured.getScheme())) {
            throw new IllegalArgumentException("attachment.public-base-url debe usar HTTP o HTTPS");
        }
        if (configured.getHost() == null || configured.getQuery() != null || configured.getFragment() != null) {
            throw new IllegalArgumentException("attachment.public-base-url no es válida");
        }
        this.publicBaseUrl = configured.toString().replaceAll("/+$", "");
    }

    public String downloadUrl(Attachment attachment) {
        Objects.requireNonNull(attachment, "attachment es obligatorio");
        if (attachment.getId() == null) {
            throw new IllegalArgumentException("attachment.id es obligatorio");
        }
        return publicBaseUrl + "/api/attachments/" + attachment.getId() + "/content";
    }
}
