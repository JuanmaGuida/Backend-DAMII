package com.reclamos.backend.exception;

public class TrackingTicketNotFoundException extends RuntimeException {
    public static final String CODE = "TRACKING_TICKET_NOT_FOUND";
    public static final String MESSAGE = "No se encontró una solicitud para el código de seguimiento indicado.";

    public TrackingTicketNotFoundException() {
        super(MESSAGE);
    }
}