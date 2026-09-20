package com.reclamos.backend.service;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.entity.Attachment;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentReferenceServiceTest {
    @Test
    void generatesStableConfiguredHttpReferenceWithoutStorageKey() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setPublicBaseUrl(URI.create("https://m2.example/base/"));
        AttachmentReferenceService service = new AttachmentReferenceService(properties);
        Attachment attachment = new Attachment();
        attachment.setId(42L);
        attachment.setStorageKey("tickets/private/internal-key");

        String reference = service.downloadUrl(attachment);

        assertEquals("https://m2.example/base/api/attachments/42/content", reference);
        assertFalse(reference.contains(attachment.getStorageKey()));
    }

    @Test
    void rejectsNonHttpBaseAndAttachmentWithoutId() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setPublicBaseUrl(URI.create("file:///private"));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentReferenceService(properties));

        properties.setPublicBaseUrl(URI.create("http://localhost:8080"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttachmentReferenceService(properties).downloadUrl(new Attachment()));
    }
}
