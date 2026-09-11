package com.reclamos.backend.controller;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Guía funcional complementaria M2 §7/§7.1: namespace /staff/tickets para
 * las vistas de gestión interna (AGENT, AREA_RESPONSIBLE, ADMIN), separado
 * de /tickets (vista ciudadana propia) y /me/tickets (listado ciudadano
 * propio). No incluye todavía ninguna acción de escritura staff — sólo los
 * dos detalles de sólo lectura pedidos hasta ahora.
 */
@RestController
@RequestMapping("/api/staff/tickets")
@RequiredArgsConstructor
public class StaffTicketController {

    private final TicketService ticketService;

    /**
     * GET /staff/tickets/{id}: detalle staff de un ticket. AGENT/ADMIN
     * acceden a cualquier ticket ajeno; AREA_RESPONSIBLE sólo a los de su
     * propia areaId (o al suyo propio como ciudadano). El filtro de rol de
     * entrada (quién puede llamar a este endpoint) vive en
     * SecurityConfiguration; el ownership/areaId real lo valida
     * TicketService.requireStaffAccess.
     */
    @GetMapping("/{ticketId}")
    public TicketResponse getStaffDetail(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity
    ) {
        return ticketService.getStaffDetail(ticketId, identity);
    }

    /**
     * GET /staff/tickets/{id}/citizen-view: misma proyección que ve el
     * ciudadano, consultada desde el contexto staff. Mismo control de
     * acceso de entrada que getStaffDetail — ver
     * TicketService.getStaffCitizenView.
     */
    @GetMapping("/{ticketId}/citizen-view")
    public TicketResponse getCitizenView(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity
    ) {
        return ticketService.getStaffCitizenView(ticketId, identity);
    }
}
