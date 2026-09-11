package com.reclamos.backend.controller;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entidades V1.49 §"Autenticación, roles y vistas": namespace /me para las
 * vistas propias del usuario autenticado, separado de /tickets (bandeja
 * staff, Story 3.1) y de /staff/tickets/{id} (detalle staff, todavía no
 * implementado).
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final TicketService ticketService;

    /**
     * GET /me/tickets: listado de los tickets propios del ciudadano
     * autenticado. Cualquier rol (CITIZEN, AGENT, AREA_RESPONSIBLE, ADMIN)
     * conserva capacidades ciudadanas base sobre sus propios tickets, así
     * que no hay restricción de rol acá — sólo estar autenticado (ver
     * SecurityConfiguration). El scoping al citizenId del requester lo hace
     * TicketService.listMyTickets.
     */
    @GetMapping("/tickets")
    public Page<TicketResponse> myTickets(
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ticketService.listMyTickets(identity, pageable);
    }
}
