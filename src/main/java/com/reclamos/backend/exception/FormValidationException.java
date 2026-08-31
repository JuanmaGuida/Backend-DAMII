package com.reclamos.backend.exception;

public class FormValidationException extends RuntimeException {
    public FormValidationException(String message) {
        super(message);
    }
}
