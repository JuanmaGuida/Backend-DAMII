package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidAttachmentException;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Replays a deterministic corpus captured against the original H31 validator before extraction. */
class AttachmentValidationParityTest {
    private final AttachmentFormatValidator validator = AttachmentFixtures.formatValidator();
    private static final String[] MIMES = {
            "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif", "application/pdf"
    };
    private final StringBuilder transcript = new StringBuilder();
    private int accepted;
    private int rejected;

    @Test
    void preservesOriginalOutcomesMessagesAndBytes() throws Exception {
        // Keep the random generator order identical to the existing H31 test.
        Random random = new Random(31);
        for (String mime : new String[]{"image/png", "image/jpeg", "image/webp", "image/heif", "application/pdf"}) {
            byte[] fixture = AttachmentFixtures.bytes(mime);
            for (int length = 0; length < 1024; length++) {
                byte[] data = new byte[length];
                random.nextBytes(data);
                if (length > 16) System.arraycopy(fixture, 0, data, 0, 12);
                check("random-31-" + length, data, mime);
            }
        }
        String[] names = {"valid.png", "valid.jpg", "progressive.jpg", "valid.webp", "lossless.webp",
                "animated.webp", "valid.heic", "valid.pdf", "xref-stream.pdf", "incremental.pdf"};
        for (String name : names) {
            byte[] fixture = AttachmentFixtures.resource(name);
            for (String mime : MIMES) {
                check(name, fixture, mime);
                for (int n : new int[]{0, 1, 8, fixture.length / 2, fixture.length - 1, fixture.length - 6}) {
                    check(name + "-truncated-" + n, Arrays.copyOf(fixture, n), mime);
                }
                for (int p = 0; p < Math.min(40, fixture.length); p++) {
                    byte[] damaged = fixture.clone();
                    damaged[p] ^= (byte) 255;
                    check(name + "-damaged-" + p, damaged, mime);
                }
                for (int p : new int[]{fixture.length / 2, fixture.length - 16, fixture.length - 1}) {
                    byte[] damaged = fixture.clone();
                    damaged[p] ^= 1;
                    check(name + "-damaged-tail-" + p, damaged, mime);
                }
                check(name + "-trailing-data", Arrays.copyOf(fixture, fixture.length + 16), mime);
            }
        }
        byte[] heif = AttachmentFixtures.bytes("image/heif");
        for (String brand : new String[]{"mif1", "avif", "avis", "mp42"}) {
            byte[] variant = heif.clone();
            for (int p : new int[]{8, 16, 20, 24}) {
                System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, variant, p, 4);
            }
            check("brands-" + brand, variant, "image/heif");
            check("brands-" + brand, variant, "image/heic");
        }
        byte[] overflow = heif.clone();
        ByteBuffer.wrap(overflow, 28, 4).putInt(1);
        ByteBuffer.wrap(overflow, 36, 8).putLong(Long.MAX_VALUE);
        check("extended-box-overflow", overflow, "image/heif");
        for (String mime : MIMES) {
            check("text", "Hello world!".getBytes(StandardCharsets.US_ASCII), mime);
            check("executable", "MZ\u0090\u0000This program cannot be run in DOS mode"
                    .getBytes(StandardCharsets.ISO_8859_1), mime);
        }
        // JPEG's accepted post-EOI camera data also exercises exact per-file limit boundaries.
        for (int n : new int[]{10 * 1024 * 1024 - 1, 10 * 1024 * 1024, 10 * 1024 * 1024 + 1}) {
            check("jpeg-size-" + n, Arrays.copyOf(AttachmentFixtures.bytes("image/jpeg"), n), "image/jpeg");
        }
        check("unsupported", AttachmentFixtures.bytes("image/png"), "application/octet-stream");
        String output = System.getProperty("attachment.parity.output");
        if (output != null) Files.writeString(Path.of(output), transcript, StandardCharsets.UTF_8);
        String digest = sha256(transcript.toString().getBytes(StandardCharsets.UTF_8));
        System.out.printf("Attachment parity: accepted=%d rejected=%d sha256=%s%n", accepted, rejected, digest);
        assertEquals(303, accepted);
        assertEquals(7890, rejected);
        assertEquals("b514f6f930685c5101b84c562336f1929c5274af03cc33f8b43097923e1d1a37", digest);
    }

    private void check(String name, byte[] data, String mime) throws Exception {
        String before = sha256(data);
        String outcome;
        try {
            validator.validate(data, mime);
            outcome = "ACCEPT";
            accepted++;
        } catch (InvalidAttachmentException exception) {
            outcome = exception.getClass().getName() + "|" + InvalidAttachmentException.CODE + "|" + exception.getMessage();
            rejected++;
        }
        assertEquals(before, sha256(data), "Input mutated: " + name + " / " + mime);
        transcript.append(name).append('\t').append(mime).append('\t').append(data.length).append('\t')
                .append(before).append('\t').append(outcome).append('\n');
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
