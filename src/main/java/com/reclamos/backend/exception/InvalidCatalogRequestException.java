package com.reclamos.backend.exception;

public class InvalidCatalogRequestException extends RuntimeException {
    public InvalidCatalogRequestException(String message) {
        super(message);
    }
}
