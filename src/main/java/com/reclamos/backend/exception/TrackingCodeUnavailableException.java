package com.reclamos.backend.exception;

public class TrackingCodeUnavailableException extends RuntimeException {
    public static final String CODE = "TRACKING_CODE_UNAVAILABLE";
    public TrackingCodeUnavailableException() {
        super("El código de seguimiento no está disponible en este momento");
    }
}
