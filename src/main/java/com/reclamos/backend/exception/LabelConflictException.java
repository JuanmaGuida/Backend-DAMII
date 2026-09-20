package com.reclamos.backend.exception;

public class LabelConflictException extends RuntimeException {
    public LabelConflictException(String message) {
        super(message);
    }
}
