package com.reclamos.backend.exception;

public class StoredFileNotFoundException extends RuntimeException {
    public static final String CODE = "ATTACHMENT_CONTENT_NOT_FOUND";

    public StoredFileNotFoundException() {
        super("El contenido del adjunto solicitado no está disponible");
    }
}
