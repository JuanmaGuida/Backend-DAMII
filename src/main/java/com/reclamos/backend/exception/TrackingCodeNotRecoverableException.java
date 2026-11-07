package com.reclamos.backend.exception;

public class TrackingCodeNotRecoverableException extends RuntimeException {
    public static final String CODE = "TRACKING_CODE_NOT_RECOVERABLE";
    public TrackingCodeNotRecoverableException() {
        super("Este ticket no dispone de un código de seguimiento recuperable");
    }
}
