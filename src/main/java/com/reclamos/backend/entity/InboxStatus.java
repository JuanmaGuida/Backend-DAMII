package com.reclamos.backend.entity;

/**
 * Estado de procesamiento de un {@link InboxEvent} (Entidades v1.3 §19.1).
 * El consumidor actual de updateTicketStatus es síncrono (todo ocurre dentro
 * de la misma transacción HTTP), así que en la práctica sólo se persiste
 * PROCESSED al final de un consumo exitoso — no se pasa por RECEIVED como
 * estado intermedio persistido, y FAILED no se usa todavía (ver javadoc de
 * TicketStatusUpdateService). Se conservan los tres valores por fidelidad
 * con el contrato para cuando exista un consumidor asíncrono real.
 */
public enum InboxStatus {
    RECEIVED,
    PROCESSED,
    FAILED
}
