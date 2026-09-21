package com.reclamos.backend.controller;

import com.reclamos.backend.dto.ClassificationCorrectionRequest;
import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.InvalidAuthenticationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketAttachmentUploadService;
import com.reclamos.backend.service.SatisfactionSurveyService;
import com.reclamos.backend.service.TicketMessageService;
import com.reclamos.backend.service.TicketResolutionService;
import com.reclamos.backend.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final InformationRequestService informationRequestService;
    private final TicketResolutionService ticketResolutionService;
    private final TicketAttachmentUploadService ticketAttachmentUploadService;
    private final SatisfactionSurveyService satisfactionSurveyService;
    private final TicketMessageService ticketMessageService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateTicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        rejectInvalidPresentedAuthorization(authorization, identity);
        return created(ticketService.create(request, identity, new MultipartFile[0]));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreateTicketResponse> createMultipart(
            @Valid @RequestPart("data") CreateTicketRequest request,
            @RequestPart(value = "evidence", required = false) MultipartFile[] evidence,
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        rejectInvalidPresentedAuthorization(authorization, identity);
        return created(ticketService.create(request, identity, evidence));
    }

    private void rejectInvalidPresentedAuthorization(String authorization, AuthenticatedIdentity identity) {
        if (authorization != null && identity == null) {
            throw new InvalidAuthenticationException();
        }
    }

    private ResponseEntity<CreateTicketResponse> created(CreateTicketResponse response) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
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
            @RequestParam(required = false) Set<UUID> labelIds,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        TicketFilter filter = new TicketFilter(categoryId, priority, neighborhoodId, responsibleAreaId, status, labelIds);
        return ticketService.listTickets(filter, pageable);
    }

    /**
     * GET /tickets/{id} (Entidades V1.49): detalle ciudadano de un ticket,
     * sólo para su propietario autenticado.
     */
    @GetMapping("/{ticketId}")
    public TicketDetailResponse getById(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity
    ) {
        return ticketService.getById(ticketId, identity);
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

    /** Finaliza la clasificación e inicia gestión propia o deriva al área externa responsable. */
    @PostMapping("/{ticketId}/route")
    public TicketResponse routeToArea(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity actor
    ) {
        return ticketService.routeToArea(ticketId, actor);
    }

    @PostMapping(path = "/{ticketId}/information-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public InformationRequestResponse requestInformation(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateInformationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return informationRequestService.requestInformation(ticketId, request, identity);
    }

    @PostMapping(path = "/{ticketId}/information-response", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InformationRequestResponse answerInformation(
            @PathVariable UUID ticketId,
            @Valid @RequestBody AnswerInformationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return informationRequestService.answerInformation(ticketId, request, identity);
    }

    @PostMapping(path = "/{ticketId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.List<TicketAttachmentResponse> uploadAttachments(
            @PathVariable UUID ticketId,
            @Valid @RequestPart("data") AttachmentUploadRequest request,
            @RequestPart("attachments") MultipartFile[] attachments,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketAttachmentUploadService.upload(ticketId, request, attachments, identity);
    }

    @PostMapping(path = "/{ticketId}/information-response", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InformationRequestResponse answerInformationMultipart(
            @PathVariable UUID ticketId,
            @Valid @RequestPart("data") AnswerInformationRequest request,
            @RequestPart(value = "attachments", required = false) MultipartFile[] attachments,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return informationRequestService.answerInformation(ticketId, request, identity, attachments);
    }

    @PostMapping(path = "/{ticketId}/resolution", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResolutionResponse resolve(
            @PathVariable UUID ticketId,
            @Valid @RequestBody ResolveTicketRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketResolutionService.resolveManually(ticketId, request, identity);
    }

    @PostMapping("/{ticketId}/resolution/confirm")
    public TicketResolutionActionResponse confirmResolution(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketResolutionService.confirm(ticketId, identity);
    }

    @PostMapping(path = "/{ticketId}/resolution/reopen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TicketResolutionActionResponse reopenResolution(
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReopenTicketRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketResolutionService.reopen(ticketId, request, identity);
    }

    @PostMapping(path = "/{ticketId}/satisfaction-survey", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SatisfactionSurveyResponse createSatisfactionSurvey(
            @PathVariable UUID ticketId,
            @Valid @RequestBody SatisfactionSurveyRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return satisfactionSurveyService.create(ticketId, request, identity);
    }

    /**
     * POST /tickets/{id}/cancel: el owner sólo puede cancelar en REGISTERED;
     * AGENT/ADMIN conservan la cancelación administrativa de tickets ajenos
     * en REGISTERED/IN_REVIEW/PENDING_INFORMATION. El ownership/rol real lo
     * valida TicketService.requireCancelAuthority.
     */
    @PostMapping(path = "/{ticketId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TicketResponse cancel(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CancelTicketRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketService.cancelTicket(ticketId, request, identity);
    }

    /**
     * POST /tickets/{id}/messages (Entidades V1.49 §10 + tabla de endpoints):
     * chat de ticket. La autorización real (owner→PUBLIC únicamente,
     * AGENT/ADMIN cualquier ajeno, AREA_RESPONSIBLE sólo su areaId, ningún
     * rol interno en su propio ticket) vive en TicketMessageService, no acá.
     */
    @PostMapping(path = "/{ticketId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TicketMessageResponse createMessage(
            @PathVariable UUID ticketId,
            @Valid @RequestBody TicketMessageRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketMessageService.create(ticketId, request, identity);
    }

    /**
     * GET /tickets/{id}/messages: listado del chat. Ver
     * TicketMessageService.requireReadAuthority para el criterio de qué ve
     * cada caller (no está documentado como fila propia en Entidades V1.49,
     * que sólo especifica el POST).
     */
    @GetMapping("/{ticketId}/messages")
    public List<TicketMessageResponse> listMessages(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketMessageService.list(ticketId, identity);
    }

    /**
     * PATCH /tickets/{id}/messages/{messageId}: sólo el propio autor puede
     * editar su mensaje, y sólo el texto (visibility es inmutable). Ver
     * TicketMessageService.findOwnMessage.
     */
    @PatchMapping(path = "/{ticketId}/messages/{messageId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TicketMessageResponse updateMessage(
            @PathVariable UUID ticketId,
            @PathVariable Long messageId,
            @Valid @RequestBody TicketMessageUpdateRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketMessageService.update(ticketId, messageId, request, identity);
    }

    /**
     * DELETE /tickets/{id}/messages/{messageId}: sólo el propio autor puede
     * borrar su mensaje. El TicketActivity espejo insertado al crearlo
     * queda intacto (ver TicketMessageService).
     */
    @DeleteMapping("/{ticketId}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(
            @PathVariable UUID ticketId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        ticketMessageService.delete(ticketId, messageId, identity);
    }
}
