package com.reclamos.backend.exception;

public class UnauthorizedTicketOperationException extends RuntimeException {
    public UnauthorizedTicketOperationException() { super("No está autorizado para realizar esta operación"); }
}