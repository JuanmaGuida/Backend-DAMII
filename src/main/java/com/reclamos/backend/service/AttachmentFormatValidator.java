package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidAttachmentException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded format checks only: no pixel/document decoding, malware scan or active-content analysis. */
@Component
public final class AttachmentFormatValidator {
    private final Map<String, AttachmentContentValidationStrategy> strategies;

    public AttachmentFormatValidator(List<AttachmentContentValidationStrategy> strategies) {
        Map<String, AttachmentContentValidationStrategy> registry = new HashMap<>();
        for (AttachmentContentValidationStrategy strategy : strategies) {
            for (String mime : strategy.supportedMimeTypes()) {
                if (registry.putIfAbsent(mime, strategy) != null) {
                    throw new IllegalStateException("Hay más de una estrategia de validación para el MIME " + mime);
                }
            }
        }
        this.strategies = Map.copyOf(registry);
    }

    public void validate(byte[] data, String declaredType) {
        AttachmentContentValidationStrategy strategy = strategies.get(declaredType);
        if (strategy == null || !strategy.isValid(data, declaredType)) {
            throw new InvalidAttachmentException(
                    "El contenido del archivo no corresponde al formato declarado o está incompleto.");
        }
    }
}
