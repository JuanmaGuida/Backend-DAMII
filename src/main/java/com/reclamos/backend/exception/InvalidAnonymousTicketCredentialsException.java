package com.reclamos.backend.exception;

public class InvalidAnonymousTicketCredentialsException extends RuntimeException {
    public static final String CODE = "INVALID_ANONYMOUS_TICKET_CREDENTIALS";

    public InvalidAnonymousTicketCredentialsException() {
        super("Las credenciales del ticket anónimo no son válidas");
    }
}
