package com.reclamos.backend.entity;

/**
 * updateType publicados en el evento ticketUpdated (Eventos v1.6 §7.2).
 * Distinto de {@link UpdateTicketStatusType}, que es el updateType que M2
 * CONSUME en updateTicketStatus (§8.1) — son dos enums separados aunque
 * comparten algunos nombres, porque describen direcciones opuestas del
 * contrato.
 */
public enum TicketUpdatedType {
    STATUS_CHANGED,
    ROUTED,
    PROGRESS,
    PRIORITY_CHANGED,
    ESCALATION_CHANGED,
    INFORMATION_REQUIRED,
    INFORMATION_PROVIDED,
    DUPLICATE_LINKED,
    REOPENED,
    RESOLVED,
    CLOSED,
    CANCELLED,
    CONTENT_UPDATED
}
