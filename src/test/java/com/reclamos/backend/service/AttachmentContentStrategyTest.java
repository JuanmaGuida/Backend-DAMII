package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidAttachmentException;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentContentStrategyTest {
    private static final String ERROR =
            "El contenido del archivo no corresponde al formato declarado o está incompleto.";

    @Test
    void selectsOnlyTheDeclaredMimeAndPassesTheOriginalBytes() {
        AttachmentContentValidationStrategy png = strategy(Set.of("image/png"));
        AttachmentContentValidationStrategy heif = strategy(Set.of("image/heic", "image/heif"));
        AttachmentFormatValidator validator = new AttachmentFormatValidator(List.of(png, heif));
        byte[] bytes = AttachmentFixtures.bytes("image/heif");
        when(heif.isValid(bytes, "image/heif")).thenReturn(true);
        validator.validate(bytes, "image/heif");
        verify(heif).isValid(same(bytes), eq("image/heif"));
        verify(png, never()).isValid(any(), any());
        verifyNoMoreInteractionsAfterRegistration(png, heif);
    }

    @Test
    void rejectionDoesNotTryAnotherAlgorithmOrChangeTheOriginalError() {
        AttachmentContentValidationStrategy png = strategy(Set.of("image/png"));
        AttachmentContentValidationStrategy jpeg = strategy(Set.of("image/jpeg"));
        AttachmentFormatValidator validator = new AttachmentFormatValidator(List.of(png, jpeg));
        byte[] bytes = AttachmentFixtures.bytes("image/jpeg");
        assertOriginalRejection(validator, bytes, "image/png");
        verify(png).isValid(same(bytes), eq("image/png"));
        verify(jpeg, never()).isValid(any(), any());
        verifyNoMoreInteractionsAfterRegistration(png, jpeg);
    }

    @Test
    void duplicateMimeIsRejectedRegardlessOfRegistrationOrder() {
        AttachmentContentValidationStrategy heif = strategy(Set.of("image/heic", "image/heif"));
        AttachmentContentValidationStrategy duplicate = strategy(Set.of("image/heif"));
        for (List<AttachmentContentValidationStrategy> order : List.of(List.of(heif, duplicate), List.of(duplicate, heif))) {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> new AttachmentFormatValidator(order));
            assertEquals("Hay más de una estrategia de validación para el MIME image/heif", exception.getMessage());
        }
        assertThrows(IllegalStateException.class, () -> new AttachmentFormatValidator(List.of(heif, heif)));
    }

    @Test
    void unknownOrMissingMimeHasOriginalRejectionWithoutCallingAnyAlgorithm() {
        AttachmentContentValidationStrategy png = strategy(Set.of("image/png"));
        AttachmentFormatValidator validator = new AttachmentFormatValidator(List.of(png));
        byte[] bytes = AttachmentFixtures.bytes("image/png");
        for (String mime : List.of("image/jpeg", "application/octet-stream", "", "IMAGE/PNG")) {
            assertOriginalRejection(validator, bytes, mime);
        }
        assertOriginalRejection(new AttachmentFormatValidator(List.of()), bytes, "image/png");
        verify(png, never()).isValid(any(), any());
    }

    @Test
    void registryIsASnapshotOfTheInjectedStrategies() {
        AttachmentContentValidationStrategy png = strategy(Set.of("image/png"));
        List<AttachmentContentValidationStrategy> strategies = new ArrayList<>(List.of(png));
        AttachmentFormatValidator validator = new AttachmentFormatValidator(strategies);
        strategies.clear();
        byte[] bytes = AttachmentFixtures.bytes("image/png");
        when(png.isValid(bytes, "image/png")).thenReturn(true);
        assertDoesNotThrow(() -> validator.validate(bytes, "image/png"));
    }

    @Test
    void realStrategiesRejectCrossFormatBytesAndPreserveHeifHeicDistinction() {
        AttachmentFormatValidator validator = AttachmentFixtures.formatValidator();
        List<String> mimes = List.of("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif", "application/pdf");
        for (String actual : mimes) {
            byte[] bytes = AttachmentFixtures.bytes(actual);
            byte[] original = bytes.clone();
            for (String declared : mimes) {
                boolean sameFamily = actual.equals(declared)
                        || (actual.startsWith("image/hei") && declared.startsWith("image/hei"));
                if (sameFamily) assertDoesNotThrow(() -> validator.validate(bytes, declared));
                else assertOriginalRejection(validator, bytes, declared);
                assertArrayEquals(original, bytes);
            }
        }
        byte[] genericHeif = AttachmentFixtures.bytes("image/heif");
        for (int p : new int[]{8, 16, 20, 24}) {
            System.arraycopy(new byte[]{'m', 'i', 'f', '1'}, 0, genericHeif, p, 4);
        }
        assertDoesNotThrow(() -> validator.validate(genericHeif, "image/heif"));
        assertOriginalRejection(validator, genericHeif, "image/heic");
    }

    private AttachmentContentValidationStrategy strategy(Set<String> types) {
        AttachmentContentValidationStrategy strategy = mock(AttachmentContentValidationStrategy.class);
        when(strategy.supportedMimeTypes()).thenReturn(types);
        return strategy;
    }

    private void assertOriginalRejection(AttachmentFormatValidator validator, byte[] bytes, String mime) {
        InvalidAttachmentException exception = assertThrows(InvalidAttachmentException.class,
                () -> validator.validate(bytes, mime));
        assertEquals(ERROR, exception.getMessage());
        assertEquals("INVALID_ATTACHMENT", InvalidAttachmentException.CODE);
    }

    private void verifyNoMoreInteractionsAfterRegistration(AttachmentContentValidationStrategy... strategies) {
        for (AttachmentContentValidationStrategy strategy : strategies) verify(strategy).supportedMimeTypes();
        verifyNoMoreInteractions((Object[]) strategies);
    }
}
