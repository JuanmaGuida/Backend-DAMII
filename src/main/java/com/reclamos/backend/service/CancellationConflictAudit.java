package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.TicketActivityRepository;
import java.time.Instant;
import java.util.*;

/** Scope conflicts reuse the existing private activity history; they are not citizen requests. */
final class CancellationConflictAudit {
    static final String REASON = "CANCELLATION_SCOPE_REQUIRED";
    static void record(TicketActivityRepository repository, Ticket ticket, String origin,
            CancellationReasonCode reason, UUID externalEventId, UUID informationRequestId,
            List<UUID> duplicates, TicketStatus previousStatus, Instant now,
            String publicMessage,String internalMessage) {
        var history=repository.findAllByTicket_IdOrderBySequenceAsc(ticket.getId());
        if(history.stream().anyMatch(a -> REASON.equals(a.getReasonCode()) &&
                (externalEventId != null && externalEventId.equals(a.getExternalEventId())
                 || informationRequestId != null && informationRequestId.toString().equals(metadata(a).get("informationRequestId"))))) return;
        TicketActivity a=new TicketActivity();a.setTicket(ticket);a.setSequence(repository.countByTicketId(ticket.getId())+1);
        a.setActionType(ActivityType.STATE_CHANGED);a.setPreviousStatus(previousStatus);a.setNewStatus(ticket.getCurrentStatus());
        a.setActorType(ActorType.SYSTEM);a.setSourceModuleId("M2");a.setReasonCode(REASON);
        a.setExternalEventId(externalEventId);a.setOccurredAt(now);a.setMessage(origin+": requiere revisión administrativa de alcance");
        Map<String,Object> data=new LinkedHashMap<>();data.put("origin",origin);data.put("reasonCode",reason.name());
        data.put("expectedStatus",ticket.getCurrentStatus().name());data.put("statusChangedAt",ticket.getStatusChangedAt().toString());
        data.put("duplicateSnapshot",duplicates.stream().map(UUID::toString).toList());
        if(informationRequestId != null) data.put("informationRequestId",informationRequestId.toString());
        if(publicMessage != null) data.put("publicMessage",publicMessage);
        if(internalMessage != null) data.put("internalMessage",internalMessage);
        a.setMetadata(data);repository.saveAndFlush(a);
    }
    static Map<String,Object> metadata(TicketActivity a) { return a.getMetadata()==null?Map.of():a.getMetadata(); }
    static Optional<TicketActivity> decision(List<TicketActivity> history,Long id) {
        return history.stream().filter(a -> id.toString().equals(String.valueOf(metadata(a).get("cancellationConflictId"))))
                .filter(a -> a.getActionType()==ActivityType.CANCELLATION_APPROVED || a.getActionType()==ActivityType.CANCELLATION_REJECTED).findFirst();
    }
    private CancellationConflictAudit() {}
}
