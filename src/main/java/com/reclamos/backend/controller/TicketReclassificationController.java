package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.ReclassificationProposalResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.TicketReclassificationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/staff/tickets/{ticketId}/reclassification-proposals")
public class TicketReclassificationController {
    private final TicketReclassificationService service;

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Proponer reclasificación provisional", description = "AGENT/ADMIN sobre ticket ajeno IN_REVIEW. Retorna READY o WAITING_INFORMATION sin cambiar su clasificación efectiva. 403 sin autoridad; 409 por estado, propuesta abierta o ausencia de RETURNED autorizado; 400 por datos inválidos.")
    public ReclassificationProposalResponse propose(@PathVariable UUID ticketId,
            @Valid @RequestBody ProposeReclassificationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor) {
        return service.propose(ticketId, request, actor);
    }

    @GetMapping
    @Operation(summary = "Consultar historial administrativo de propuestas", description = "Sólo AGENT/ADMIN. El historial y los snapshots nunca se incluyen en eventos ni proyecciones ciudadanas.")
    public List<ReclassificationProposalResponse> list(@PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.list(ticketId, actor); }

    @GetMapping("/{proposalId}")
    @Operation(summary = "Consultar propuesta y requisitos pendientes", description = "404 si la propuesta no pertenece al ticket; 403 sin autoridad staff.")
    public ReclassificationProposalResponse get(@PathVariable UUID ticketId, @PathVariable Long proposalId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.get(ticketId, proposalId, actor); }

    @PostMapping("/{proposalId}/confirm")
    @Operation(summary = "Confirmar reclasificación e iniciar gestión", description = "Exige READY, IN_REVIEW y autorización vigente. Revalida formulario versionado, ubicación, evidencia y anonimato. Atómico e idempotente por propuesta. 409 si incompleta o incompatible; 422 EVIDENCE_REQUIRED si falta evidencia.")
    public ReclassificationProposalResponse confirm(@PathVariable UUID ticketId, @PathVariable Long proposalId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.confirm(ticketId, proposalId, actor); }

    @PostMapping("/{proposalId}/abandon")
    @Operation(summary = "Abandonar propuesta con motivo", description = "Conserva la clasificación real y el historial. Cancela su pedido pendiente y restaura IN_REVIEW sin derivar ni cancelar el Ticket.")
    public ReclassificationProposalResponse abandon(@PathVariable UUID ticketId, @PathVariable Long proposalId,
            @Valid @RequestBody AbandonReclassificationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.abandon(ticketId, proposalId, request.reason(), actor); }
}
