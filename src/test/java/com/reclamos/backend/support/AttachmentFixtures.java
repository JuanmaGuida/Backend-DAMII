package com.reclamos.backend.support;

import com.reclamos.backend.service.AttachmentFormatValidator;
import com.reclamos.backend.service.HeifContentValidationStrategy;
import com.reclamos.backend.service.JpegContentValidationStrategy;
import com.reclamos.backend.service.PdfContentValidationStrategy;
import com.reclamos.backend.service.PngContentValidationStrategy;
import com.reclamos.backend.service.WebpContentValidationStrategy;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

public final class AttachmentFixtures {
    private AttachmentFixtures() { }

    public static AttachmentFormatValidator formatValidator() {
        return new AttachmentFormatValidator(List.of(
                new PngContentValidationStrategy(),
                new JpegContentValidationStrategy(),
                new WebpContentValidationStrategy(),
                new HeifContentValidationStrategy(),
                new PdfContentValidationStrategy()));
    }

    public static byte[] bytes(String mime) {
        String extension = switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException(mime);
        };
        return resource("valid." + extension);
    }

    public static byte[] resource(String name) {
        try (InputStream input = AttachmentFixtures.class.getResourceAsStream("/attachments/" + name)) {
            if (input == null) throw new IllegalArgumentException(name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static MockMultipartFile file(String part, String name, String mime) {
        return new MockMultipartFile(part, name, mime, bytes(mime));
    }

    public static com.reclamos.backend.service.AttachmentService.ValidatedAttachment validated(String name, String mime) {
        var properties = new com.reclamos.backend.config.AttachmentProperties();
        properties.getLimits().setMaxFiles(5);
        properties.getLimits().setMaxFileSize(org.springframework.util.unit.DataSize.ofMegabytes(10));
        properties.getLimits().setMaxTotalSize(org.springframework.util.unit.DataSize.ofMegabytes(50));
        properties.setAllowedContentTypes(java.util.Set.of(mime));
        return new com.reclamos.backend.service.AttachmentService(null, null, properties, formatValidator())
                .validate(new MockMultipartFile[]{file("attachments", name, mime)}).getFirst();
    }
}
