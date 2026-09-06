package com.reclamos.backend.exception;

public class InvalidAttachmentException extends RuntimeException {
    public static final String CODE = "INVALID_ATTACHMENT";

    public InvalidAttachmentException(String message) {
        super(message);
    }
}
