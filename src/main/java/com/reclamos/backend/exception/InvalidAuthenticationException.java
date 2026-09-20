package com.reclamos.backend.exception;

public class InvalidAuthenticationException extends RuntimeException {
    public InvalidAuthenticationException() {
        super("La sesión no es válida");
    }
}
