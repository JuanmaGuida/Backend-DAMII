package com.reclamos.backend.service;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.exception.InvalidAttachmentException;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class AttachmentContentStrategySpringContextTest {
    @Autowired private List<AttachmentContentValidationStrategy> strategies;
    @Autowired private AttachmentFormatValidator validator;
    @Autowired private AttachmentService service;
    @Autowired private AttachmentProperties properties;

    @Test
    void springRegistersFiveAlgorithmsForAllSixMimesAndInjectsTheService() {
        assertEquals(5, strategies.size());
        Set<String> mimes = strategies.stream().flatMap(strategy -> strategy.supportedMimeTypes().stream())
                .collect(Collectors.toSet());
        assertEquals(Set.of("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif", "application/pdf"), mimes);
        assertEquals(mimes, properties.getAllowedContentTypes());
        assertEquals(6, strategies.stream().mapToInt(strategy -> strategy.supportedMimeTypes().size()).sum());
        assertSame(validator, ReflectionTestUtils.getField(service, "formatValidator"));
        for (String mime : mimes) {
            byte[] bytes = AttachmentFixtures.bytes(mime);
            assertDoesNotThrow(() -> validator.validate(bytes, mime));
            assertEquals(bytes.length, service.validate(new MultipartFile[]{
                    new MockMultipartFile("evidence", "safe.bin", mime, bytes)}).getFirst().sizeBytes());
        }
        assertThrows(InvalidAttachmentException.class, () -> service.validate(new MultipartFile[]{
                new MockMultipartFile("evidence", "fake.png", "image/png", AttachmentFixtures.bytes("application/pdf"))}));
    }
}
