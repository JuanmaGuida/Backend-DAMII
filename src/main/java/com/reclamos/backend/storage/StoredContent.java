package com.reclamos.backend.storage;

import org.springframework.core.io.Resource;

import java.util.Objects;

public record StoredContent(Resource resource, long sizeBytes) {
    public StoredContent {
        Objects.requireNonNull(resource, "resource es obligatorio");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes debe ser positivo");
        }
    }
}
