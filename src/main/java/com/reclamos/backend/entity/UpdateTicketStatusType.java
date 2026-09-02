package com.reclamos.backend.entity;

/**
 * updateType que M2 CONSUME en el evento updateTicketStatus (Eventos v1.6
 * §8.1), enviado por un módulo operativo. Distinto de
 * {@link TicketUpdatedType}, que es lo que M2 PUBLICA.
 */
public enum UpdateTicketStatusType {
    STARTED,
    PROGRESS,
    INFORMATION_REQUIRED,
    RETURNED,
    RESOLVED,
    REJECTED
}
