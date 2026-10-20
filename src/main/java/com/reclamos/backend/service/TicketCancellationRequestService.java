package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.CancellationRequestResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service @RequiredArgsConstructor
public class TicketCancellationRequestService {
    private final TicketRepository tickets;
    private final TicketCancellationRequestRepository requests;
    private final TicketActivityRepository activities;
    private final TicketService ticketService;
    private final Clock clock;

    @Transactional
    public CancellationRequestResponse create(UUID ticketId, CreateCancellationRequest input,
                                               AuthenticatedIdentity actor) {
        Ticket ticket = locked(ticketId);
        requireOwner(ticket, actor);
        return create(ticket, input);
    }

    /** ticketId comes exclusively from the existing code+password accreditation. */
    @Transactional
    public CancellationRequestResponse createAnonymous(UUID ticketId, CreateCancellationRequest input) {
        Ticket ticket = locked(ticketId);
        requireAnonymous(ticket);
        return create(ticket, input);
    }

    private CancellationRequestResponse create(Ticket ticket, CreateCancellationRequest input) {
        if (ticket.getCurrentStatus() != TicketStatus.REGISTERED || ticket.getMainTicket() != null) {
            throw conflict("La solicitud ciudadana requiere un principal REGISTERED");
        }
        List<UUID> duplicates = tickets.findActiveDuplicateIds(ticket.getId());
        if (duplicates.isEmpty()) throw conflict("Sin duplicados activos corresponde la cancelación directa");
        if (requests.existsByTicket_IdAndStatus(ticket.getId(), CancellationRequestStatus.PENDING)) {
            throw conflict("Ya existe una solicitud PENDING; consulte su estado");
        }
        requireText(input.reason());
        if (input.reasonCode() == null) throw new InvalidTicketRequestException("reasonCode es obligatorio");
        TicketCancellationRequest r = new TicketCancellationRequest();
        r.setTicket(ticket); r.setRequestedByCitizenId(ticket.getCitizenId());
        r.setRequesterKind(ticket.isAnonymous() ? CancellationRequesterKind.ANONYMOUS : CancellationRequesterKind.IDENTIFIED);
        r.setReason(input.reason().strip()); r.setReasonCode(input.reasonCode());
        r.setStatus(CancellationRequestStatus.PENDING); r.setRequestedAt(clock.instant());
        r.setDuplicateSnapshot(duplicates); r.setRequestedStatusChangedAt(ticket.getStatusChangedAt());
        requests.saveAndFlush(r);
        audit(r, ActivityType.CANCELLATION_REQUESTED, ActorType.CITIZEN,
                ticket.getCitizenId(), ticket.isAnonymous() ? "Solicitud de propietario anónimo acreditado" : "Solicitud del propietario");
        return response(r);
    }

    @Transactional(readOnly = true)
    public List<CancellationRequestResponse> ownHistory(UUID ticketId, AuthenticatedIdentity actor) {
        requireOwner(found(ticketId), actor);
        return history(ticketId, false);
    }

    @Transactional(readOnly = true)
    public List<CancellationRequestResponse> anonymousHistory(UUID ticketId) {
        requireAnonymous(found(ticketId));
        return history(ticketId, false);
    }

    @Transactional(readOnly = true)
    public List<CancellationRequestResponse> staffHistory(UUID ticketId, AuthenticatedIdentity actor) {
        requireStaff(actor);
        found(ticketId);
        return history(ticketId, true);
    }

    @Transactional(readOnly = true)
    public CancellationRequestResponse staffGet(UUID ticketId, UUID requestId, AuthenticatedIdentity actor) {
        requireStaff(actor);
        return response(request(ticketId, requestId));
    }

    @Transactional
    public CancellationRequestResponse approve(UUID ticketId, UUID requestId,
            ReviewCancellationRequest input, AuthenticatedIdentity actor) {
        Ticket ticket = locked(ticketId);
        ticketService.requireTriageAuthority(ticket, actor);
        TicketCancellationRequest r = request(ticketId, requestId);
        requireText(input.decisionReason());
        if (input.cancellationScope() == null) throw new InvalidTicketRequestException("cancellationScope es obligatorio al aprobar");
        if (r.getStatus() == CancellationRequestStatus.APPROVED) {
            if (r.getCancellationScope() != input.cancellationScope()
                    || !r.getDecisionReason().equals(input.decisionReason().strip())) {
                throw conflict("La solicitud ya fue aprobada con otra decisión");
            }
            return response(r);
        }
        if (r.getStatus() != CancellationRequestStatus.PENDING) throw conflict("La solicitud ya fue rechazada");
        if (ticket.getCurrentStatus() != TicketStatus.REGISTERED || ticket.getMainTicket() != null
                || !ticket.getStatusChangedAt().equals(r.getRequestedStatusChangedAt())
                || !tickets.findActiveDuplicateIds(ticketId).equals(r.getDuplicateSnapshot())) {
            throw conflict("Solicitud desactualizada: cambió el estado o el conjunto de duplicados; requiere revisión");
        }
        CancelTicketRequest cancellation = new CancelTicketRequest();
        cancellation.setReasonCode(r.getReasonCode());
        cancellation.setPublicMessage(input.publicMessage());
        cancellation.setInternalMessage(r.getReason());
        cancellation.setCancellationScope(input.cancellationScope());
        cancellation.setDecisionReason(input.decisionReason().strip());
        ticketService.cancelWithScope(ticket, cancellation, actor);
        decide(r, CancellationRequestStatus.APPROVED, input, actor);
        return response(r);
    }

    @Transactional
    public CancellationRequestResponse reject(UUID ticketId, UUID requestId,
            ReviewCancellationRequest input, AuthenticatedIdentity actor) {
        Ticket ticket = locked(ticketId);
        ticketService.requireTriageAuthority(ticket, actor);
        TicketCancellationRequest r = request(ticketId, requestId);
        requireText(input.decisionReason());
        if (input.cancellationScope() != null) throw new InvalidTicketRequestException("El rechazo no admite cancellationScope");
        if (r.getStatus() == CancellationRequestStatus.REJECTED
                && r.getDecisionReason().equals(input.decisionReason().strip())) return response(r);
        if (r.getStatus() != CancellationRequestStatus.PENDING) throw conflict("La solicitud ya fue decidida");
        decide(r, CancellationRequestStatus.REJECTED, input, actor);
        return response(r);
    }

    private void decide(TicketCancellationRequest r, CancellationRequestStatus status,
                        ReviewCancellationRequest input, AuthenticatedIdentity actor) {
        r.setStatus(status); r.setReviewedAt(clock.instant()); r.setReviewedBy(actor.citizenId());
        r.setDecisionReason(input.decisionReason().strip()); r.setCancellationScope(input.cancellationScope());
        requests.save(r);
        audit(r, status == CancellationRequestStatus.APPROVED ? ActivityType.CANCELLATION_APPROVED
                : ActivityType.CANCELLATION_REJECTED, actor.role() == ModuleRole.ADMIN ? ActorType.ADMIN
                : ActorType.AGENT, actor.citizenId(), r.getDecisionReason());
    }

    private List<CancellationRequestResponse> history(UUID id, boolean staff) {
        return requests.findAllByTicket_IdOrderByRequestedAtAscIdAsc(id).stream().map(r -> response(r, staff)).toList();
    }
    private TicketCancellationRequest request(UUID ticketId, UUID id) {
        return requests.findByIdAndTicket_Id(id, ticketId).orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
    }
    private Ticket locked(UUID id) {
        return tickets.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
    }
    private Ticket found(UUID id) {
        return tickets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
    }
    private void requireOwner(Ticket ticket, AuthenticatedIdentity actor) {
        if (actor == null || ticket.isAnonymous() || !actor.citizenId().equals(ticket.getCitizenId()))
            throw new UnauthorizedTicketOperationException();
    }
    private void requireAnonymous(Ticket ticket) {
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null) throw new UnauthorizedTicketOperationException();
    }
    private void requireStaff(AuthenticatedIdentity actor) {
        if (actor == null || actor.role() != ModuleRole.AGENT && actor.role() != ModuleRole.ADMIN)
            throw new UnauthorizedTicketOperationException();
    }
    private void requireText(String text) {
        if (text == null || text.isBlank() || text.length() > 4000)
            throw new InvalidTicketRequestException("El motivo es obligatorio y admite hasta 4000 caracteres");
    }
    private TicketStateConflictException conflict(String message) { return new TicketStateConflictException(message); }
    private CancellationRequestResponse response(TicketCancellationRequest r) {
        return response(r, true);
    }
    private CancellationRequestResponse response(TicketCancellationRequest r, boolean staff) {
        return new CancellationRequestResponse(r.getId(), r.getTicket().getId(), r.getRequestedByCitizenId(),
                r.getRequesterKind(), r.getReason(), r.getReasonCode(), r.getStatus(), r.getRequestedAt(),
                r.getReviewedAt(), staff ? r.getReviewedBy() : null, r.getCancellationScope(), staff ? r.getDecisionReason() : null);
    }
    private void audit(TicketCancellationRequest r, ActivityType type, ActorType actorType, UUID actor, String message) {
        Ticket t = r.getTicket();
        TicketActivity a = new TicketActivity(); a.setTicket(t);
        a.setSequence(activities.countByTicketId(t.getId()) + 1); a.setActionType(type);
        a.setPreviousStatus(t.getCurrentStatus()); a.setNewStatus(t.getCurrentStatus());
        a.setActorType(actorType); a.setActorId(actor == null ? null : actor.toString());
        a.setSourceModuleId("M2"); a.setMessage(message); a.setOccurredAt(clock.instant());
        a.setMetadata(Map.of("cancellationRequestId", r.getId().toString(), "requesterKind", r.getRequesterKind().name()));
        activities.save(a);
    }
}
