package com.reclamos.backend.exception;

public class UnsupportedAttachmentMediaTypeException extends RuntimeException {
    public static final String CODE = "UNSUPPORTED_MEDIA_TYPE";

    public UnsupportedAttachmentMediaTypeException(String message) {
        super(message);
    }
}
