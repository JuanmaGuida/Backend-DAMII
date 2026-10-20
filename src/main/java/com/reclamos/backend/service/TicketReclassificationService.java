package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.ReclassificationProposalResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class TicketReclassificationService {
    private final TicketRepository tickets;
    private final TicketReclassificationProposalRepository proposals;
    private final RequestTypeRepository types;
    private final TicketActivityRepository activities;
    private final InboxEventRepository inbox;
    private final InformationRequestRepository requests;
    private final AttachmentRepository attachments;
    private final InformationRequestService information;
    private final ReclassificationDataService data;
    private final FormValidationService forms;
    private final RiskCalculationService risks;
    private final PriorityCalculationService priorities;
    private final TicketSlaService sla;
    private final TicketSlaMilestoneService milestones;
    private final TicketOutboxService outbox;
    private final Clock clock;

    @Transactional
    public ReclassificationProposalResponse propose(UUID ticketId, ProposeReclassificationRequest request,
                                                     AuthenticatedIdentity actor) {
        Ticket ticket = locked(ticketId, actor);
        UUID returnEvent = authorization(ticket);
        if (proposals.findOpen(ticketId).isPresent()) throw conflict("Abandone la propuesta abierta con motivo antes de sustituirla");
        if (requests.existsByTicketIdAndStatus(ticketId, InformationRequestStatus.PENDING)) throw conflict("Ya hay una solicitud pendiente");
        RequestType target = types.findByIdForUpdate(request.requestTypeId()).filter(RequestType::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("El Request Type no existe o está inactivo"));
        if (target.getId().equals(ticket.getRequestType().getId())) throw conflict("Elija un Request Type diferente");
        if (target.getResponsibleAreaId() == null || target.getResponsibleAreaId().isBlank()) throw conflict("El tipo no tiene área responsable");
        Instant now = clock.instant();
        TicketReclassificationProposal p = new TicketReclassificationProposal();
        p.setTicket(ticket); p.setTargetRequestType(target);
        FormTemplate template = forms.resolveActiveTemplate(target);
        p.setTargetFormTemplateId(template == null ? null : template.getId());
        p.setOrigin(returnEvent == null ? ReclassificationOrigin.INITIAL_REVIEW : ReclassificationOrigin.REQUEST_TYPE_MISMATCH);
        p.setAuthorizingReturnEventId(returnEvent);
        p.setStatus(ReclassificationProposalStatus.PROPOSED);
        p.setProposedData(data.initialData(ticket, request.formData()));
        p.setPreviousClassificationSnapshot(data.snapshot(ticket));
        p.setProposedBy(actor.citizenId().toString()); p.setCreatedAt(now); p.setUpdatedAt(now);
        data.form(p, p.getProposedData(), false);
        proposals.saveAndFlush(p);
        audit(p, actor, "RECLASSIFICATION_PROPOSED", "Propuesta de cambio al tipo " + target.getCode(),
                ticket.getCurrentStatus(), null, now);
        if (data.missingCitizenData(p)) {
            String message = request.messageForCitizen() == null || request.messageForCitizen().isBlank()
                    ? "Complete la información pendiente para revisar la clasificación de su ticket."
                    : request.messageForCitizen().strip();
            InformationRequest info = information.requestReclassification(ticket, message, actor);
            p.setInformationRequestId(info.getId());
            p.setStatus(ReclassificationProposalStatus.WAITING_INFORMATION);
        } else {
            data.validateComplete(p, p.getProposedData(), false);
            p.setStatus(ReclassificationProposalStatus.READY);
        }
        return response(proposals.save(p));
    }

    @Transactional(readOnly = true)
    public List<ReclassificationProposalResponse> list(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
        requireStaff(actor);
        return proposals.findAllByTicket_IdOrderByIdAsc(ticketId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public ReclassificationProposalResponse get(UUID ticketId, Long proposalId, AuthenticatedIdentity actor) {
        Ticket ticket = tickets.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
        requireStaff(actor);
        return response(proposal(ticketId, proposalId));
    }

    @Transactional
    public ReclassificationProposalResponse confirm(UUID ticketId, Long proposalId, AuthenticatedIdentity actor) {
        Ticket ticket = locked(ticketId, actor);
        TicketReclassificationProposal p = proposal(ticketId, proposalId);
        // A retry returns the historical result without reapplying any business effects.
        if (p.getStatus() == ReclassificationProposalStatus.CONFIRMED) return response(p);
        UUID authorization = authorization(ticket);
        if (!Objects.equals(authorization, p.getAuthorizingReturnEventId())) throw conflict("La autorización de la propuesta ya no está vigente");
        if (p.getStatus() != ReclassificationProposalStatus.READY) throw conflict("La propuesta no está READY");
        if (requests.existsByTicketIdAndStatus(ticketId, InformationRequestStatus.PENDING)) throw conflict("Hay una solicitud pendiente");
        RequestType target = types.findByIdForUpdate(p.getTargetRequestType().getId()).filter(RequestType::isActive)
                .orElseThrow(() -> conflict("El tipo destino ya no está habilitado"));
        if (target.getResponsibleAreaId() == null || target.getResponsibleAreaId().isBlank()) throw conflict("El tipo no tiene área responsable");
        if (ticket.isAnonymous() && !target.isAllowsAnonymous()) throw conflict("El tipo exige identificación válida de M1; no puede confirmarse un ticket anónimo");
        p.setTargetRequestType(target);
        data.validateComplete(p, p.getProposedData(), false);
        ResolvedForm form = data.form(p, p.getProposedData(), true);
        Instant now = clock.instant();
        Priority oldPriority = ticket.getCurrentPriority();
        ticket.setRequestType(target); ticket.setTicketType(target.getTicketType());
        ticket.setFormTemplateId(form.formTemplateId()); ticket.setFormData(new LinkedHashMap<>(form.formData()));
        ticket.setResponsibleAreaId(target.getResponsibleAreaId());
        TicketLocation location = data.applyLocation(p);
        ticket.setEstimatedAffectedCount(data.estimatedAffected(target, location));
        RiskAssessment risk = risks.calculateRisk(target, form);
        boolean exceptional = p.getOrigin() == ReclassificationOrigin.REQUEST_TYPE_MISMATCH;
        var age = exceptional ? new PriorityCalculationService.AgeFraction(0, 1)
                : priorities.age(sla.findLatestResolutionCycle(ticket).orElse(null), now);
        ticket.setCurrentPriority(priorities.calculate(risk.score(), ticket.getEstimatedAffectedCount(), age, target.getMinimumPriority()));
        TicketStatus previous = ticket.getCurrentStatus();
        ticket.setCurrentStatus("M2".equalsIgnoreCase(target.getResponsibleAreaId()) ? TicketStatus.IN_PROGRESS : TicketStatus.ROUTED);
        ticket.setStatusChangedAt(now);
        if (ticket.getClassificationFinalizedAt() == null) ticket.setClassificationFinalizedAt(now);
        tickets.save(ticket);
        // Promote only this proposal's public evidence. Abandoned attempts remain isolated.
        attachments.findAllByReclassificationProposalIdOrderByCreatedAtAsc(p.getId())
                .forEach(a -> a.setReclassificationProposalId(null));
        Optional<TicketSla> cycle = exceptional ? sla.startReclassifiedResolutionCycle(ticket, now)
                : sla.recalculateInitialResolutionCycle(ticket, now);
        milestones.reconcileReclassificationEscalation(ticket, now);
        audit(p, actor, "RECLASSIFICATION_CONFIRMED", "Clasificación confirmada: " + target.getCode(), previous, oldPriority, now);
        p.setStatus(ReclassificationProposalStatus.CONFIRMED); p.setConfirmedBy(actor.citizenId().toString());
        p.setResolvedAt(now); p.setUpdatedAt(now); proposals.save(p);
        Instant due = cycle.map(TicketSla::getDueAt).orElse(null);
        if (ticket.getCurrentStatus() == TicketStatus.ROUTED) outbox.routed(ticket, location, due, now, exceptional);
        else outbox.contentUpdated(ticket, due, now);
        tickets.flush();
        return response(p);
    }

    @Transactional
    public ReclassificationProposalResponse abandon(UUID ticketId, Long proposalId, String reason, AuthenticatedIdentity actor) {
        Ticket ticket = locked(ticketId, actor);
        if (reason == null || reason.isBlank()) throw new InvalidTicketRequestException("El motivo de abandono es obligatorio");
        TicketReclassificationProposal p = proposal(ticketId, proposalId);
        if (p.getStatus() == ReclassificationProposalStatus.ABANDONED) return response(p);
        if (p.getStatus() == ReclassificationProposalStatus.CONFIRMED) throw conflict("Una propuesta confirmada es final");
        Instant now = clock.instant();
        TicketStatus previous = ticket.getCurrentStatus();
        if (p.getInformationRequestId() != null) {
            InformationRequest info = requests.findByIdAndTicketIdForUpdate(p.getInformationRequestId(), ticketId).orElseThrow();
            if (info.getStatus() == InformationRequestStatus.PENDING) {
                if (!info.getDueAt().isAfter(now)) throw new InformationRequestExpiredException();
                if (ticket.getCurrentStatus() != TicketStatus.PENDING_INFORMATION) throw conflict("Estado incompatible con la solicitud pendiente");
                info.setStatus(InformationRequestStatus.CANCELLED); requests.save(info);
                sla.resumeActiveResolutionCycle(ticket, now);
                ticket.setCurrentStatus(TicketStatus.IN_REVIEW); ticket.setStatusChangedAt(now); tickets.save(ticket);
            }
        }
        p.setStatus(ReclassificationProposalStatus.ABANDONED); p.setAbandonmentReason(reason.strip());
        p.setUpdatedAt(now); p.setResolvedAt(now); proposals.save(p);
        audit(p, actor, "RECLASSIFICATION_ABANDONED", reason.strip(), previous, null, now);
        if (previous != ticket.getCurrentStatus()) outbox.statusChanged(ticket, "Se retomó la revisión del ticket.", now);
        return response(p);
    }

    /** The persisted, accepted external fact grants the exception, never IN_REVIEW alone. */
    UUID authorization(Ticket ticket) {
        if (ticket.getCurrentStatus() != TicketStatus.IN_REVIEW) throw conflict("La propuesta requiere IN_REVIEW");
        if (ticket.getClassificationFinalizedAt() == null) return null;
        List<TicketActivity> history = activities.findAllByTicket_IdOrderBySequenceAsc(ticket.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            TicketActivity a = history.get(i);
            if (a.getActionType() == ActivityType.RETURNED_BY_AREA) {
                if (!"REQUEST_TYPE_MISMATCH".equals(a.getReasonCode()) || a.getNewStatus() != TicketStatus.IN_REVIEW
                        || a.getExternalEventId() == null || !ticket.getResponsibleAreaId().equalsIgnoreCase(a.getSourceModuleId())) break;
                boolean accepted = inbox.findById(a.getExternalEventId()).filter(e -> e.getStatus() == InboxStatus.PROCESSED
                        && "updateTicketStatus".equals(e.getEventType())
                        && ticket.getResponsibleAreaId().equalsIgnoreCase(e.getProducerModuleId())).isPresent();
                if (accepted) return a.getExternalEventId();
                break;
            }
            if (a.getActionType() == ActivityType.REQUEST_TYPE_CHANGED || a.getActionType() == ActivityType.ROUTED
                    || a.getActionType() == ActivityType.REOPENED || a.getActionType() == ActivityType.DUPLICATE_LINKED
                    || a.getNewStatus() == TicketStatus.IN_PROGRESS) break;
        }
        throw conflict("Se requiere RETURNED/REQUEST_TYPE_MISMATCH aceptado del módulo responsable");
    }

    private Ticket locked(UUID ticketId, AuthenticatedIdentity actor) {
        if (actor == null || actor.role() != ModuleRole.AGENT && actor.role() != ModuleRole.ADMIN) throw new UnauthorizedTicketOperationException();
        Ticket ticket = tickets.findByIdForUpdate(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
        requireStaff(actor);
        if (Objects.equals(ticket.getCitizenId(), actor.citizenId())) throw new UnauthorizedTicketOperationException();
        return ticket;
    }

    private void requireStaff(AuthenticatedIdentity actor) {
        if (actor == null || actor.role() != ModuleRole.AGENT && actor.role() != ModuleRole.ADMIN) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private TicketReclassificationProposal proposal(UUID ticketId, Long id) {
        return proposals.findByIdAndTicket_Id(id, ticketId).orElseThrow(() -> new ResourceNotFoundException("Propuesta no encontrada"));
    }

    private ReclassificationProposalResponse response(TicketReclassificationProposal p) {
        boolean terminal = p.getResolvedAt() != null;
        return new ReclassificationProposalResponse(p.getId(), p.getTicket().getId(), p.getOrigin(), p.getStatus(),
                p.getTargetRequestType().getId(), p.getTargetFormTemplateId(), p.getProposedData(), p.getPreviousClassificationSnapshot(),
                p.getInformationRequestId(), p.getProposedBy(), p.getConfirmedBy(), p.getAbandonmentReason(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getResolvedAt(), terminal ? null : data.requirements(p));
    }

    private void audit(TicketReclassificationProposal p, AuthenticatedIdentity actor, String reason, String message,
                       TicketStatus previous, Priority oldPriority, Instant now) {
        Ticket ticket = p.getTicket();
        TicketActivity a = new TicketActivity(); a.setTicket(ticket);
        a.setSequence(activities.countByTicketId(ticket.getId()) + 1);
        a.setActionType(switch (reason) {
            case "RECLASSIFICATION_PROPOSED" -> ActivityType.RECLASSIFICATION_PROPOSED;
            case "RECLASSIFICATION_ABANDONED" -> ActivityType.RECLASSIFICATION_ABANDONED;
            default -> ActivityType.REQUEST_TYPE_CHANGED;
        });
        a.setPreviousStatus(previous); a.setNewStatus(ticket.getCurrentStatus());
        a.setActorType(actor.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT);
        a.setActorId(actor.citizenId().toString()); a.setSourceModuleId("M2");
        a.setReasonCode(reason); a.setMessage(message); a.setOccurredAt(now);
        a.setPreviousPriority(oldPriority); a.setNewPriority(oldPriority == null ? null : ticket.getCurrentPriority());
        a.setMetadata(Map.of("proposalId", p.getId(), "origin", p.getOrigin().name(), "targetRequestTypeId", p.getTargetRequestType().getId()));
        activities.save(a);
    }

    private TicketStateConflictException conflict(String message) { return new TicketStateConflictException(message); }
}
