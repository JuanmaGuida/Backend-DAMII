package com.reclamos.backend.controller;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.service.TicketStatusUpdateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Story 3.4 (BE - Endpoint interno, solo accesible para QA/desarrollo, que
 * simula updateTicketStatus / DDA2-61). Aplica exactamente la misma lógica
 * de transición que se usará con el evento real, delegando en
 * {@link TicketStatusUpdateService} (DDA2-62) — a propósito, para que dar
 * de baja el simulador el día de mañana no requiera tocar esa lógica.
 * <p>
 * Restricción de acceso: este controller SÓLO se registra como bean cuando
 * la propiedad app.simulator.enabled=true está activa (ver
 * application-dev.properties). En cualquier otro perfil/entorno el bean no
 * existe y el endpoint no existe — no es un chequeo de rol (todavía no hay
 * enforcement de permisos), es un apagado a nivel de entorno, que es lo que
 * pide el AC ("no debe estar disponible para usuarios finales").
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.simulator.enabled", havingValue = "true")
public class TicketSimulationController {

    private final TicketStatusUpdateService ticketStatusUpdateService;

    @PostMapping("/{ticketId}/simulate-status-update")
    public TicketResponse simulateStatusUpdate(
            @PathVariable UUID ticketId,
            @Valid @RequestBody UpdateTicketStatusRequest request
    ) {
        return ticketStatusUpdateService.applyUpdate(ticketId, request);
    }
}
