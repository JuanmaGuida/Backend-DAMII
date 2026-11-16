package com.reclamos.backend.service;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.entity.Attachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentReferenceServiceTest {
    @ParameterizedTest
    @CsvSource({
            "https://m2.example, https://m2.example",
            "https://m2.example/, https://m2.example",
            "https://m2.example////, https://m2.example",
            "http://localhost:8080/base//part///, http://localhost:8080/base//part",
            "HTTPS://M2.example:443/base/%2F///, HTTPS://M2.example:443/base/%2F",
            "https://m2.example/base/%2f, https://m2.example/base/%2f"
    })
    void removesOnlyLiteralTrailingSlashes(String base, String expected) {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setPublicBaseUrl(URI.create(base));
        Attachment attachment = new Attachment();
        attachment.setId(42L);
        assertEquals(expected + "/api/attachments/42/content",
                new AttachmentReferenceService(properties).downloadUrl(attachment));
    }

    @Test
    void handlesALongTrailingSlashSuffixWithoutBacktracking() {
        removesOnlyLiteralTrailingSlashes("https://m2.example/base" + "/".repeat(100_000),
                "https://m2.example/base");
    }

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
