package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidAttachmentException;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentFormatValidatorTest {
    private final AttachmentFormatValidator validator = AttachmentFixtures.formatValidator();

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf"})
    void acceptsRealEncodedFilesAndRejectsTruncation(String mime) {
        byte[] valid = AttachmentFixtures.bytes(mime);
        assertDoesNotThrow(() -> validator.validate(valid, mime));
        for (int length : new int[]{0, 1, 8, valid.length / 2, valid.length - 1}) {
            // A PDF may legitimately end with or without its final newline.
            if (mime.equals("application/pdf") && length == valid.length - 1) length -= 6;
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(InvalidAttachmentException.class, () -> validator.validate(truncated, mime));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf"})
    void rejectsExecutableTextAndOtherAllowedFormatsUnderDeclaredMime(String mime) {
        byte[] text = "Hello world!".getBytes(StandardCharsets.US_ASCII); // original 12-byte repro
        byte[] exe = "MZ\u0090\u0000This program cannot be run in DOS mode".getBytes(StandardCharsets.ISO_8859_1);
        byte[] other = AttachmentFixtures.bytes(mime.equals("image/png") ? "application/pdf" : "image/png");
        for (byte[] invalid : new byte[][]{text, exe, other}) {
            assertThrows(InvalidAttachmentException.class, () -> validator.validate(invalid, mime));
        }
    }

    @Test
    void acceptsProgressiveLosslessAndAnimatedVariants() {
        validator.validate(AttachmentFixtures.resource("progressive.jpg"), "image/jpeg");
        validator.validate(AttachmentFixtures.resource("lossless.webp"), "image/webp");
        validator.validate(AttachmentFixtures.resource("animated.webp"), "image/webp");
        validator.validate(AttachmentFixtures.resource("xref-stream.pdf"), "application/pdf");
        validator.validate(AttachmentFixtures.resource("incremental.pdf"), "application/pdf");
    }

    @Test
    void jpegIdentificationDoesNotAddARestrictionOnCameraDataAfterEoi() {
        byte[] jpeg = AttachmentFixtures.bytes("image/jpeg");
        byte[] withCameraPayload = Arrays.copyOf(jpeg, jpeg.length + 16);
        validator.validate(withCameraPayload, "image/jpeg");
    }

    @Test
    void rejectsPngWithBrokenCrcDimensionsOrMissingImageData() {
        byte[] crc = AttachmentFixtures.bytes("image/png");
        crc[crc.length - 1] ^= 1;
        assertInvalid(crc, "image/png");
        byte[] dimension = AttachmentFixtures.bytes("image/png");
        Arrays.fill(dimension, 16, 20, (byte) 0);
        CRC32 checksum = new CRC32();
        checksum.update(dimension, 12, 17);
        ByteBuffer.wrap(dimension, 29, 4).putInt((int) checksum.getValue());
        assertInvalid(dimension, "image/png");
        byte[] oversizedChunk = AttachmentFixtures.bytes("image/png");
        Arrays.fill(oversizedChunk, 8, 12, (byte) 255);
        assertInvalid(oversizedChunk, "image/png");
        byte[] zlib = AttachmentFixtures.bytes("image/png");
        for (int p = 8; p < zlib.length;) {
            int n = ByteBuffer.wrap(zlib, p, 4).getInt();
            if (new String(zlib, p + 4, 4, StandardCharsets.US_ASCII).equals("IDAT")) {
                zlib[p + 8] = 0;
                CRC32 crc32 = new CRC32();
                crc32.update(zlib, p + 4, n + 4);
                ByteBuffer.wrap(zlib, p + 8 + n, 4).putInt((int) crc32.getValue());
                break;
            }
            p += n + 12;
        }
        assertInvalid(zlib, "image/png");
        assertInvalid(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0}, "image/png");
    }

    @Test
    void rejectsJpegWithOnlyMarkersOrImpossibleSegmentLength() {
        assertInvalid(new byte[]{(byte) 255, (byte) 216, (byte) 255, (byte) 217}, "image/jpeg");
        byte[] invalid = AttachmentFixtures.bytes("image/jpeg");
        invalid[4] = (byte) 255;
        invalid[5] = (byte) 255;
        assertInvalid(invalid, "image/jpeg");
    }

    @Test
    void rejectsPdfWithOnlyHeaderOrForgedEofAndInvalidXref() {
        assertInvalid("%PDF-1.7\nplain text\n%%EOF\n".getBytes(StandardCharsets.US_ASCII), "application/pdf");
        byte[] pdf = AttachmentFixtures.bytes("application/pdf");
        String invalid = new String(pdf, StandardCharsets.ISO_8859_1)
                .replaceAll("startxref\\s+[0-9]+", "startxref\n99999999");
        assertInvalid(invalid.getBytes(StandardCharsets.ISO_8859_1), "application/pdf");
        String brokenTable = new String(pdf, StandardCharsets.ISO_8859_1)
                .replaceFirst("0000000000 [0-9]{5} f", "not-an-xref-entry");
        assertInvalid(brokenTable.getBytes(StandardCharsets.ISO_8859_1), "application/pdf");
        String excessiveEntries = new String(pdf, StandardCharsets.ISO_8859_1)
                .replaceFirst("xref\\s+0 [0-9]+", "xref\n0 9999999999");
        assertInvalid(excessiveEntries.getBytes(StandardCharsets.ISO_8859_1), "application/pdf");
    }

    @Test
    void rejectsWebpWithBrokenRiffSizeAndEmptyImageChunk() {
        byte[] invalid = AttachmentFixtures.bytes("image/webp");
        invalid[4] ^= 1;
        assertInvalid(invalid, "image/webp");
        byte[] empty = new byte[]{'R','I','F','F',12,0,0,0,'W','E','B','P','V','P','8','L',0,0,0,0};
        assertInvalid(empty, "image/webp");
    }

    @Test
    void rejectsHeifWithoutMetadataPayloadOrWithAvifOrMovieBrand() {
        byte[] original = AttachmentFixtures.bytes("image/heic");
        for (String brand : new String[]{"avif", "mp42"}) {
            byte[] invalid = original.clone();
            byte[] replacement = brand.getBytes(StandardCharsets.US_ASCII);
            // Replace both major and compatible brands, including mif1.
            for (int p : new int[]{8, 16, 20, 24}) System.arraycopy(replacement, 0, invalid, p, 4);
            assertInvalid(invalid, "image/heif");
            assertInvalid(invalid, "image/heic");
        }
        assertInvalid(Arrays.copyOf(original, 28), "image/heic");
        byte[] boxOverflow = original.clone();
        Arrays.fill(boxOverflow, 28, 32, (byte) 255);
        assertInvalid(boxOverflow, "image/heif");
        byte[] extendedOverflow = original.clone();
        ByteBuffer.wrap(extendedOverflow, 28, 4).putInt(1);
        ByteBuffer.wrap(extendedOverflow, 36, 8).putLong(Long.MAX_VALUE);
        assertInvalid(extendedOverflow, "image/heif");
    }

    @Test
    void arbitraryAndDamagedInputsAlwaysReturnControlledRejection() {
        Random random = new Random(31);
        for (String mime : new String[]{"image/png", "image/jpeg", "image/webp", "image/heif", "application/pdf"}) {
            byte[] fixture = AttachmentFixtures.bytes(mime);
            for (int length = 0; length < 1024; length++) {
                byte[] invalid = new byte[length];
                random.nextBytes(invalid);
                if (length > 16) System.arraycopy(fixture, 0, invalid, 0, 12);
                assertInvalid(invalid, mime);
            }
        }
    }

    private void assertInvalid(byte[] data, String mime) {
        assertThrows(InvalidAttachmentException.class, () -> validator.validate(data, mime));
    }
}
