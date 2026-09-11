package com.reclamos.backend.storage;

public record StoredFile(String storageKey, long sizeBytes) {
    public StoredFile {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey es obligatorio");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes debe ser positivo");
        }
    }
}
