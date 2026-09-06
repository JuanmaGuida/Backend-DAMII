package com.reclamos.backend.exception;

public class AttachmentPayloadTooLargeException extends RuntimeException {
    public static final String CODE = "PAYLOAD_TOO_LARGE";

    public AttachmentPayloadTooLargeException(String message) {
        super(message);
    }
}
