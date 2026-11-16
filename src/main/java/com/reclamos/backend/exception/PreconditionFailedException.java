package com.reclamos.backend.exception;

public class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException() {
        super("If-Match no coincide con la versión actual del recurso");
    }
}
