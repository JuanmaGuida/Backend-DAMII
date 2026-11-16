package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.CancellationRequestResponse;
import com.reclamos.backend.dto.response.CancellationConflictResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class TicketCancellationRequestController {
    private final TicketCancellationRequestService service;
    private final AnonymousTicketAccessService anonymousAccess;
    private final TicketCancellationConflictService conflicts;

    @PostMapping("/api/tickets/{ticketId}/cancellation-requests") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Solicitar revisión de cancelación", description = "Owner M1 de un principal REGISTERED con duplicados activos. No cancela el ticket. 400 motivo inválido; 401 sin login; 403 sin ownership; 404 inexistente; 409 por estado, sin duplicados o PENDING existente.")
    public CancellationRequestResponse create(@PathVariable UUID ticketId,
            @Valid @RequestBody CreateCancellationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.create(ticketId, request, actor); }

    @GetMapping("/api/tickets/{ticketId}/cancellation-requests")
    @Operation(summary = "Consultar historial propio de solicitudes", description = "Sólo owner autenticado M1. Incluye estado y scope; reviewedBy y decisionReason internos se omiten. Sin snapshots ni credenciales.")
    public List<CancellationRequestResponse> own(@PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.ownHistory(ticketId, actor); }

    @PostMapping("/api/tracking/actions/cancellation-requests")
    @Operation(summary = "Solicitar cancelación como propietario anónimo", description = "trackingCode + anonymousAccessPassword en body, con payload reasonCode y reason. 401 credenciales incorrectas; 409 requiere principal REGISTERED con duplicados. No cancela el ticket.")
    public ResponseEntity<CancellationRequestResponse> anonymousCreate(
            @Valid @RequestBody AnonymousTicketActionRequest<@Valid CreateCancellationRequest> request) {
        var access = anonymousAccess.authenticate(request.trackingCode(), request.anonymousAccessPassword());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.createAnonymous(access.ticketId(), request.payload()));
    }

    @PostMapping("/api/tracking/actions/cancellation-requests/history")
    @Operation(summary = "Consultar solicitudes de un anónimo acreditado", description = "Exige código y contraseña. No amplía /tracking/access read-only. Sin credenciales en URL ni respuestas cacheables.")
    public ResponseEntity<List<CancellationRequestResponse>> anonymousHistory(
            @Valid @RequestBody AnonymousTicketCredentialsRequest request) {
        var access = anonymousAccess.authenticate(request.trackingCode(), request.anonymousAccessPassword());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.anonymousHistory(access.ticketId()));
    }

    @GetMapping("/api/staff/tickets/{ticketId}/cancellation-requests")
    @Operation(summary = "Listar solicitudes del ticket para revisión", description = "Sólo AGENT/ADMIN; pueden consultar sus tickets propios en modo read-only.")
    public List<CancellationRequestResponse> staff(@PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.staffHistory(ticketId, actor); }

    @GetMapping("/api/staff/tickets/{ticketId}/cancellation-requests/{requestId}")
    public CancellationRequestResponse get(@PathVariable UUID ticketId, @PathVariable UUID requestId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.staffGet(ticketId, requestId, actor); }

    @PostMapping("/api/staff/tickets/{ticketId}/cancellation-requests/{requestId}/approve")
    @Operation(summary = "Aprobar GLOBAL o PRINCIPAL_ONLY", description = "AGENT/ADMIN sobre todos los tickets afectados ajenos. Exige scope explícito y decisionReason. Revalida estado y duplicados bajo lock. Atómico e idempotente para la misma decisión. 400 sin scope/motivo; 403 sin autoridad; 404 solicitud ajena; 409 si desactualizada, ya rechazada o con otra decisión/concurrencia.")
    public CancellationRequestResponse approve(@PathVariable UUID ticketId, @PathVariable UUID requestId,
            @Valid @RequestBody ReviewCancellationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.approve(ticketId, requestId, request, actor); }

    @PostMapping("/api/staff/tickets/{ticketId}/cancellation-requests/{requestId}/reject")
    @Operation(summary = "Rechazar con justificación", description = "AGENT/ADMIN sobre ticket ajeno. Exige decisionReason y scope null. Conserva el ticket y el historial. 409 si ya aprobada o rechazada con otro motivo.")
    public CancellationRequestResponse reject(@PathVariable UUID ticketId, @PathVariable UUID requestId,
            @Valid @RequestBody ReviewCancellationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return service.reject(ticketId, requestId, request, actor); }

    @GetMapping("/api/staff/tickets/{ticketId}/cancellation-conflicts")
    @Operation(summary="Consultar conflictos de terminación automática o externa",description="Sólo AGENT/ADMIN. INFO_TIMEOUT registra EXPIRED y devuelve el principal a IN_REVIEW con RESOLUTION reanudado; REJECTED externo mantiene el ticket y registra sólo el conflicto, sin Inbox PROCESSED ni evento espejo.")
    public List<CancellationConflictResponse> conflicts(@PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return conflicts.list(ticketId,actor); }

    @PostMapping("/api/staff/tickets/{ticketId}/cancellation-conflicts/{conflictId}/resolve")
    @Operation(summary="Resolver conflicto con decisión administrativa",description="AGENT/ADMIN ajeno. decision=APPROVED exige scope y motivo; REJECTED exige motivo y scope null. Revalida estado y duplicados. Única vía administrativa que puede cancelar ROUTED/IN_PROGRESS tras REJECTED auditado. Idempotente para la misma decisión; 409 si cambió el conjunto, el estado o la decisión.")
    public CancellationConflictResponse resolve(@PathVariable UUID ticketId,@PathVariable Long conflictId,
            @Valid @RequestBody ResolveCancellationConflictRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor) { return conflicts.resolve(ticketId,conflictId,request,actor); }
}
