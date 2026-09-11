package com.reclamos.backend.exception;

public class TicketResolutionConflictException extends RuntimeException {
    public static final String CODE = "TICKET_RESOLUTION_CONFLICT";

    public TicketResolutionConflictException(String message) {
        super(message);
    }
}