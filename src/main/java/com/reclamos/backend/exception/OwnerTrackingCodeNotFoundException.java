package com.reclamos.backend.exception;

public class OwnerTrackingCodeNotFoundException extends RuntimeException {
    public OwnerTrackingCodeNotFoundException() {
        super("El ticket solicitado no existe");
    }
}
