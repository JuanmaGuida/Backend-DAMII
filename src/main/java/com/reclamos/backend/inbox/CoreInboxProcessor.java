package com.reclamos.backend.inbox;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.CancellationScopeReviewRequiredException;
import com.reclamos.backend.repository.InboxEventRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.service.TicketStatusUpdateCommand;
import com.reclamos.backend.service.TicketStatusUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.reclamos.backend.inbox.IncomingEventFailure.Code.*;

@Service
public class CoreInboxProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CoreInboxProcessor.class);
    private static final Map<String, String> MODULES = Map.of("ciudadanos", "M1", "atencion-ciudadana", "M2",
            "obras", "M3", "habilitaciones", "M4", "rentas", "M5", "ambiente", "M6",
            "transito", "M7", "desarrollo-social", "M8", "core", "M9");
    private final CoreIncomingEventValidator validator;
    private final InboxEventRepository inbox;
    private final TicketRepository tickets;
    private final TicketStatusUpdateService updates;
    private final CitizenEventProjection citizens;
    private final Clock clock;

    public CoreInboxProcessor(CoreIncomingEventValidator validator, InboxEventRepository inbox, TicketRepository tickets,
                              TicketStatusUpdateService updates, CitizenEventProjection citizens, Clock clock) {
        this.validator = validator; this.inbox = inbox; this.tickets = tickets;
        this.updates = updates; this.citizens = citizens; this.clock = clock;
    }

    /** The AMQP adapter ACKs only after this proxied method has committed successfully. */
    @Transactional(noRollbackFor = CancellationScopeReviewRequiredException.class)
    public void process(byte[] body) {
        CoreIncomingEvent event = validator.decode(body);
        if (!event.eventType().equals("updateTicketStatus") && !event.eventType().equals("citizenUpdated")) {
            LOG.info("Core Inbox eventId={} code=IGNORED_OTHER_EVENT", event.eventId());
            return;
        }
        inbox.lockEvent(event.eventId());
        InboxEvent existing = inbox.findById(event.eventId()).orElse(null);
        if (existing != null) {
            if (!event.eventType().equals(existing.getEventType()) || (existing.getPayloadHash() != null
                    && !event.hash().equals(existing.getPayloadHash()))) throw new IncomingEventFailure(EVENT_ID_COLLISION);
            if (existing.getStatus() == InboxStatus.PROCESSED) return;
            throw new IncomingEventFailure(INVALID_EVENT);
        }
        String result;
        if (event.eventType().equals("citizenUpdated")) {
            citizens.apply(event); result = "PROJECTED";
        } else result = statusUpdate(event);
        InboxEvent record = inbox.findById(event.eventId()).orElseGet(InboxEvent::new);
        record.setEventId(event.eventId()); record.setEventType(event.eventType());
        record.setProducerModuleId(MODULES.getOrDefault(event.sourceModule(), "UNKNOWN"));
        record.setReceivedAt(clock.instant()); record.setProcessedAt(clock.instant()); record.setStatus(InboxStatus.PROCESSED);
        record.setPayloadHash(event.hash()); record.setError(null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceModule", event.sourceModule()); metadata.put("occurredAt", event.occurredAt().toString());
        metadata.put("correlationId", event.correlationId()); metadata.put("causationId", event.causationId()); metadata.put("disposition", result);
        record.setPayload(metadata); // No raw personal data, DNI/CUIL, address or private messages in Inbox.
        inbox.saveAndFlush(record);
        LOG.info("Core Inbox eventId={} code={}", event.eventId(), result);
    }

    private String statusUpdate(CoreIncomingEvent event) {
        String module = MODULES.get(event.sourceModule());
        if (module == null || module.equals("M1") || module.equals("M2") || module.equals("M9")) return "IGNORED_PRODUCER";
        var request = validator.statusRequest(event);
        Ticket ticket = tickets.findByIdForUpdate(request.ticketId()).orElse(null);
        if (ticket == null) return "IGNORED_UNKNOWN_TICKET";
        if (!module.equals(ticket.getResponsibleAreaId())) return "IGNORED_OTHER_AREA";
        if (ticket.getExternalUpdateAt() != null && request.updateOccurredAt().isBefore(ticket.getExternalUpdateAt())) return "IGNORED_OLD_UPDATE";
        if (request.updateType() == UpdateTicketStatusType.PROGRESS && ticket.getCurrentStatus() == TicketStatus.ROUTED)
            throw new IncomingEventFailure(PREMATURE_PROGRESS);
        boolean applicable = switch (request.updateType()) {
            case STARTED -> ticket.getCurrentStatus() == TicketStatus.ROUTED;
            case PROGRESS -> ticket.getCurrentStatus() == TicketStatus.IN_PROGRESS;
            default -> ticket.getCurrentStatus() == TicketStatus.ROUTED || ticket.getCurrentStatus() == TicketStatus.IN_PROGRESS;
        };
        if (!applicable) return "IGNORED_STATE_CONFLICT";
        if (event.data().path("attachments").size() > 0) throw new IncomingEventFailure(EXTERNAL_ATTACHMENTS_UNSUPPORTED);
        try {
            updates.applyCoreUpdate(new TicketStatusUpdateCommand(request.ticketId(), event.eventId(), event.eventType(),
                    event.occurredAt(), module, event.correlationId(), request));
            ticket.setExternalUpdateAt(request.updateOccurredAt());
            return "APPLIED";
        } catch (CancellationScopeReviewRequiredException conflict) {
            return "IGNORED_CANCELLATION_SCOPE_REVIEW";
        }
    }
}
