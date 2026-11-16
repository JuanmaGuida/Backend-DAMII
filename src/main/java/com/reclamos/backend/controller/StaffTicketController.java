package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.LinkDuplicateRequest;
import com.reclamos.backend.dto.response.DuplicateLinkResponse;
import com.reclamos.backend.dto.response.StaffTicketDetailResponse;
import com.reclamos.backend.dto.response.DuplicateCandidateResponse;
import com.reclamos.backend.dto.response.TicketDetailResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.DuplicateCandidateService;
import com.reclamos.backend.service.DuplicateTicketService;
import com.reclamos.backend.service.TicketService;
import com.reclamos.backend.service.LabelService;
import com.reclamos.backend.dto.request.AssignLabelsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.Page;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.TicketFilter;
import org.springframework.web.bind.annotation.RequestParam;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * Guía funcional complementaria M2 §7/§7.1: namespace /staff/tickets para
 * las vistas de gestión interna (AGENT, AREA_RESPONSIBLE, ADMIN), separado
 * de /tickets (vista ciudadana propia) y /me/tickets (listado ciudadano
 * propio). Incluye el listado acotado al área autenticada.
 */
@RestController
@RequestMapping("/api/staff/tickets")
@RequiredArgsConstructor
public class StaffTicketController {

    @GetMapping("/area")
    public Page<TicketResponse> listArea(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) UUID neighborhoodId,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) Set<UUID> labelIds,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean withoutLocation,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketService.listAreaTickets(new TicketFilter(categoryId, priority,
                neighborhoodId, null, status, labelIds, search, withoutLocation), pageable, identity);
    }

    private final TicketService ticketService;
    private final DuplicateCandidateService duplicateCandidateService;
    private final LabelService labelService;
    private final DuplicateTicketService duplicateTicketService;

    @PostMapping("/{ticketId}/duplicate")
    public DuplicateLinkResponse linkDuplicate(@PathVariable UUID ticketId,
                                               @Valid @RequestBody LinkDuplicateRequest request,
                                               @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return duplicateTicketService.link(ticketId, request, identity);
    }

    @PostMapping("/{ticketId}/labels")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignLabels(@PathVariable UUID ticketId, @Valid @RequestBody AssignLabelsRequest request,
                             @AuthenticationPrincipal AuthenticatedIdentity identity) {
        labelService.assign(ticketId, request, identity);
    }

    @DeleteMapping("/{ticketId}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLabel(@PathVariable UUID ticketId, @PathVariable UUID labelId,
                            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        labelService.remove(ticketId, labelId, identity);
    }

    /**
     * GET /staff/tickets/{id}: detalle staff de un ticket. AGENT/ADMIN
     * acceden a cualquier ticket ajeno; AREA_RESPONSIBLE sólo a los de su
     * propia areaId (o al suyo propio como ciudadano). El filtro de rol de
     * entrada (quién puede llamar a este endpoint) vive en
     * SecurityConfiguration; el ownership/areaId real lo valida
     * TicketService.requireStaffAccess.
     */
    @GetMapping("/{ticketId}")
    public StaffTicketDetailResponse getStaffDetail(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity
    ) {
        return ticketService.getStaffDetail(ticketId, identity);
    }

    /**
     * GET /staff/tickets/{id}/citizen-view: proyección pública que ve el
     * ciudadano, consultada desde el contexto staff. Mismo control de acceso
     * de entrada que getStaffDetail — ver
     * TicketService.getStaffCitizenView.
     */
    @GetMapping("/{ticketId}/citizen-view")
    public TicketDetailResponse getCitizenView(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity
    ) {
        return ticketService.getStaffCitizenView(ticketId, identity);
    }

    @GetMapping("/{ticketId}/duplicate-candidates")
    public List<DuplicateCandidateResponse> getDuplicateCandidates(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity
    ) {
        return duplicateCandidateService.findCandidates(ticketId, identity);
    }
}
