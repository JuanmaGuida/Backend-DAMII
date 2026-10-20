package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.CancellationConflictResponse;
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
public class TicketCancellationConflictService {
    private final TicketRepository tickets;
    private final TicketActivityRepository activities;
    private final TicketService ticketService;
    private final Clock clock;

    @Transactional(readOnly=true)
    public List<CancellationConflictResponse> list(UUID ticketId,AuthenticatedIdentity actor) {
        requireStaff(actor);
        if(!tickets.existsById(ticketId)) throw new ResourceNotFoundException("Ticket no encontrado");
        List<TicketActivity> history=activities.findAllByTicket_IdOrderBySequenceAsc(ticketId);
        return history.stream().filter(a->CancellationConflictAudit.REASON.equals(a.getReasonCode()))
                .map(a->response(a,history)).toList();
    }

    @Transactional
    public CancellationConflictResponse resolve(UUID ticketId,Long conflictId,
            ResolveCancellationConflictRequest input,AuthenticatedIdentity actor) {
        Ticket ticket=tickets.findByIdForUpdate(ticketId).orElseThrow(()->new ResourceNotFoundException("Ticket no encontrado"));
        ticketService.requireTriageAuthority(ticket,actor);
        List<TicketActivity> history=activities.findAllByTicket_IdOrderBySequenceAsc(ticketId);
        TicketActivity conflict=history.stream().filter(a->a.getId().equals(conflictId)
                && CancellationConflictAudit.REASON.equals(a.getReasonCode())).findFirst()
                .orElseThrow(()->new ResourceNotFoundException("Conflicto no encontrado en el ticket"));
        if(input.decision()==null || input.decision()==CancellationRequestStatus.PENDING
                || input.decisionReason()==null || input.decisionReason().isBlank() || input.decisionReason().length()>4000
                || (input.decision()==CancellationRequestStatus.APPROVED)!=(input.cancellationScope()!=null))
            throw new InvalidTicketRequestException("Exige decisión APPROVED con scope o REJECTED sin scope y justificación");
        var prior=CancellationConflictAudit.decision(history,conflictId);
        if(prior.isPresent()) {
            var existing=response(conflict,history);
            if(existing.status()!=input.decision() || existing.cancellationScope()!=input.cancellationScope()
                    || !existing.decisionReason().equals(input.decisionReason().strip()))
                throw new TicketStateConflictException("El conflicto ya fue resuelto con otra decisión");
            return existing;
        }
        var metadata=CancellationConflictAudit.metadata(conflict);
        if(input.decision()==CancellationRequestStatus.APPROVED) {
            if(!ticket.getCurrentStatus().name().equals(metadata.get("expectedStatus"))
                    || !ticket.getStatusChangedAt().equals(java.time.Instant.parse((String)metadata.get("statusChangedAt")))
                    || !tickets.findActiveDuplicateIds(ticketId).stream().map(UUID::toString).toList().equals(metadata.get("duplicateSnapshot")))
                throw new TicketStateConflictException("El conflicto está desactualizado; corresponde rechazo motivado");
            CancelTicketRequest cancellation=new CancelTicketRequest();
            cancellation.setReasonCode(CancellationReasonCode.valueOf((String)metadata.get("reasonCode")));
            cancellation.setCancellationScope(input.cancellationScope());cancellation.setDecisionReason(input.decisionReason());
            cancellation.setPublicMessage(input.publicMessage());
            cancellation.setInternalMessage("Revisión administrativa del conflicto "+conflictId);
            ticketService.cancelAfterConflict(ticket,cancellation,actor,"EXTERNAL_REJECTED".equals(metadata.get("origin")));
        }
        TicketActivity decision=new TicketActivity();decision.setTicket(ticket);
        decision.setSequence(activities.countByTicketId(ticketId)+1);
        decision.setActionType(input.decision()==CancellationRequestStatus.APPROVED?ActivityType.CANCELLATION_APPROVED:ActivityType.CANCELLATION_REJECTED);
        decision.setActorType(actor.role()==ModuleRole.ADMIN?ActorType.ADMIN:ActorType.AGENT);
        decision.setActorId(actor.citizenId().toString());decision.setSourceModuleId("M2");decision.setOccurredAt(clock.instant());
        decision.setPreviousStatus(ticket.getCurrentStatus());decision.setNewStatus(ticket.getCurrentStatus());
        decision.setMessage(input.decisionReason().strip());
        Map<String,Object> details=new LinkedHashMap<>();details.put("cancellationConflictId",conflictId.toString());
        details.put("cancellationScope",input.cancellationScope()==null?null:input.cancellationScope().name());
        decision.setMetadata(details);activities.saveAndFlush(decision);
        var resolvedHistory=new ArrayList<>(history);resolvedHistory.add(decision);return response(conflict,resolvedHistory);
    }
    private CancellationConflictResponse response(TicketActivity conflict,List<TicketActivity> history) {
        var decision=CancellationConflictAudit.decision(history,conflict.getId()).orElse(null);
        String scope=decision==null?null:(String)CancellationConflictAudit.metadata(decision).get("cancellationScope");
        return new CancellationConflictResponse(conflict.getId(),conflict.getTicket().getId(),
                (String)CancellationConflictAudit.metadata(conflict).get("origin"),
                CancellationReasonCode.valueOf((String)CancellationConflictAudit.metadata(conflict).get("reasonCode")),
                conflict.getExternalEventId(),conflict.getOccurredAt(),decision==null?CancellationRequestStatus.PENDING:
                decision.getActionType()==ActivityType.CANCELLATION_APPROVED?CancellationRequestStatus.APPROVED:CancellationRequestStatus.REJECTED,
                scope==null?null:CancellationScope.valueOf(scope),decision==null?null:decision.getMessage(),
                (String)CancellationConflictAudit.metadata(conflict).get("publicMessage"),
                (String)CancellationConflictAudit.metadata(conflict).get("internalMessage"));
    }
    private void requireStaff(AuthenticatedIdentity actor) {
        if(actor==null || actor.role()!=ModuleRole.AGENT && actor.role()!=ModuleRole.ADMIN) throw new UnauthorizedTicketOperationException();
    }
}
