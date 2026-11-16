package com.reclamos.backend.dto;

/**
 * Marca los DTO de eventos de integración cuyo schema no admite propiedades
 * adicionales (Eventos §12, {@code additionalProperties=false}). Un JSON que
 * traiga un campo no declarado en cualquiera de ellos se rechaza antes de
 * aplicar el hecho; ver {@code StrictEventPayloadConfiguration}.
 */
public interface StrictEventPayload {
}
