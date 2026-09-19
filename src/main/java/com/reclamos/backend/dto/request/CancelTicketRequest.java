package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.CancellationReasonCode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /tickets/{id}/cancel: el owner sólo puede cancelar en REGISTERED;
 * AGENT/ADMIN pueden cancelar administrativamente tickets ajenos en los
 * estados admitidos por TicketService. reasonCode es obligatorio
 * (TicketCancellation, Entidades §13.3); los mensajes son opcionales.
 * <p>
 * NO VALIDADO A PROPÓSITO: cualquier valor de CancellationReasonCode es
 * aceptado acá, incluyendo INFO_TIMEOUT/REJECTED_BY_AREA que conceptualmente
 * pertenecen a flujos automáticos/de integración (el job de vencimiento y
 * TicketStatusUpdateService respectivamente). Restringir el subconjunto
 * válido para esta vía manual queda a criterio del equipo.
 */
@Data
@NoArgsConstructor
public class CancelTicketRequest {
    @NotNull
    private CancellationReasonCode reasonCode;
    private String publicMessage;
    private String internalMessage;
}
