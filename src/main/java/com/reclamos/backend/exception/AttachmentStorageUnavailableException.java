package com.reclamos.backend.exception;

public class AttachmentStorageUnavailableException extends RuntimeException {
    public static final String CODE = "ATTACHMENT_STORAGE_UNAVAILABLE";

    public AttachmentStorageUnavailableException() {
        super("No fue posible almacenar la evidencia. Intentá nuevamente más tarde.");
    }

    public AttachmentStorageUnavailableException(Throwable cause) {
        super("No fue posible almacenar la evidencia. Intentá nuevamente más tarde.", cause);
    }
}
