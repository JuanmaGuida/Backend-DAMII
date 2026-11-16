package com.reclamos.backend.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key ya fue utilizada con una solicitud diferente");
    }
}
