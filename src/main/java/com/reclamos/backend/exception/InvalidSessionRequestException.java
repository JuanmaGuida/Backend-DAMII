package com.reclamos.backend.exception;

public class InvalidSessionRequestException extends RuntimeException {
    public InvalidSessionRequestException() { super("La solicitud de sesión no está autorizada"); }
}
