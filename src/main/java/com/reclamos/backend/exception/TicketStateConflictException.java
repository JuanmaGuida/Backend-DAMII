package com.reclamos.backend.exception;

/**
 * Se lanza cuando se intenta una transición de estado o una corrección de
 * clasificación que no es válida para el estado actual del ticket. Se mapea
 * a HTTP 409 en {@link GlobalExceptionHandler}.
 */
public class TicketStateConflictException extends RuntimeException {
    public TicketStateConflictException(String message) {
        super(message);
    }
}
