package com.reclamos.backend.controller;

import com.reclamos.backend.dto.ClassificationCorrectionRequest;
import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTicketResponse create(@Valid @RequestBody CreateTicketRequest request,
                                       @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketService.create(request, identity, new MultipartFile[0]);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTicketResponse createMultipart(
            @Valid @RequestPart("data") CreateTicketRequest request,
            @RequestPart(value = "evidence", required = false) MultipartFile[] evidence,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketService.create(request, identity, evidence);
    }

    /**
     * Story 3.1 - BE: Endpoint de listado con filtros por categoría, prioridad,
     * barrio/rol y estado. "Rol" se interpretó como responsibleAreaId — ver el
     * javadoc de TicketFilter.
     */
    @GetMapping
    public Page<TicketResponse> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) UUID neighborhoodId,
            @RequestParam(required = false) String responsibleAreaId,
            @RequestParam(required = false) TicketStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        TicketFilter filter = new TicketFilter(categoryId, priority, neighborhoodId, responsibleAreaId, status);
        return ticketService.listTickets(filter, pageable);
    }

    /**
     * Story 3.2 - BE: Endpoint de transición REGISTERED -&gt; IN_REVIEW (toma de
     * ticket por un agente).
     */
    @PostMapping("/{ticketId}/review")
    public TicketResponse startReview(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor
    ) {
        return ticketService.startReview(ticketId, actor);
    }

    /**
     * Story 3.2 - BE: Endpoint de corrección de clasificación.
     */
    @PatchMapping("/{ticketId}/classification")
    public TicketResponse correctClassification(
            @PathVariable UUID ticketId,
            @Valid @RequestBody ClassificationCorrectionRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity actor
    ) {
        return ticketService.correctClassification(ticketId, request.requestTypeId(), actor);
    }

    /**
     * Story 3.3 - BE: Endpoint de derivación (IN_REVIEW -&gt; ROUTED) +
     * publicación del evento ticketUpdated al outbox (DDA2-59).
     */
    @PostMapping("/{ticketId}/route")
    public TicketResponse routeToArea(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor
    ) {
        return ticketService.routeToArea(ticketId, actor);
    }
}
