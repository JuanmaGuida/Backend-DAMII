package com.reclamos.backend.service;

import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.CancellationReasonCode;
import com.reclamos.backend.entity.OutboxEvent;
import com.reclamos.backend.entity.OutboxStatus;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketUpdatedType;
import com.reclamos.backend.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketOutboxService {
    private static final String MODULE_ID = "M2";

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Value("${app.events.producer.module-id:M2}")
    private String producerModuleId = MODULE_ID;

    @Value("${app.events.producer.service:help-center-api}")
    private String producerService = "help-center-api";

    @Transactional(propagation = Propagation.MANDATORY)
    public void ticketCreated(Ticket ticket, TicketLocation location, List<Attachment> attachments,
                              Instant resolutionDueAt) {
        if (ticket.isAnonymous()) {
            return;
        }

        RequestType requestType = ticket.getRequestType();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("publicId", ticket.getPublicId());
        data.put("citizenId", ticket.getCitizenId());
        data.put("isAnonymous", false);
        data.put("requestType", requestType.getName());
        data.put("category", requestType.getSubcategory().getCategory().getName());
        data.put("subcategory", requestType.getSubcategory().getName());
        data.put("ticketType", ticket.getTicketType().name());
        data.put("summary", ticket.getSummary());
        data.put("description", ticket.getDescription());
        data.put("formData", ticket.getFormData());
        data.put("status", ticket.getCurrentStatus().name());
        data.put("priority", ticket.getCurrentPriority().name());
        data.put("responsibleAreaId", ticket.getResponsibleAreaId());
        data.put("location", eventLocation(location));
        data.put("resolutionDueAt", instant(resolutionDueAt));
        data.put("attachments", eventAttachments(attachments));
        data.put("createdAt", ticket.getCreatedAt().toString());
        save(ticket, "ticketCreated", null, data);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Ticket ticket, String publicMessage, Instant updatedAt) {
        if (ticket.isAnonymous()) {
            return;
        }
        saveUpdated(ticket, TicketUpdatedType.STATUS_CHANGED, publicMessage, null, List.of(), updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void routed(Ticket ticket, TicketLocation location, Instant resolutionDueAt, Instant updatedAt) {
        if (isSelfManaged(ticket)) {
            return;
        }

        RequestType requestType = ticket.getRequestType();
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("requestType", requestType.getName());
        routing.put("ticketType", ticket.getTicketType().name());
        routing.put("summary", ticket.getSummary());
        routing.put("description", ticket.getDescription());
        routing.put("formData", ticket.getFormData());
        routing.put("location", eventLocation(location));
        routing.put("resolutionDueAt", instant(resolutionDueAt));
        routing.put("escalation", eventEscalation(ticket));
        saveUpdated(ticket, TicketUpdatedType.ROUTED,
                "El ticket fue derivado al área responsable.", Map.of("routing", routing), List.of(), updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void contentUpdated(Ticket ticket, Instant resolutionDueAt, Instant updatedAt) {
        if (ticket.isAnonymous()) {
            return;
        }

        RequestType requestType = ticket.getRequestType();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestType", requestType.getName());
        content.put("category", requestType.getSubcategory().getCategory().getName());
        content.put("subcategory", requestType.getSubcategory().getName());
        content.put("ticketType", ticket.getTicketType().name());
        content.put("summary", ticket.getSummary());
        content.put("description", ticket.getDescription());
        content.put("formData", ticket.getFormData());
        content.put("resolutionDueAt", instant(resolutionDueAt));
        saveUpdated(ticket, TicketUpdatedType.CONTENT_UPDATED, null,
                Map.of("content", content), List.of(), updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void informationProvided(Ticket ticket, String responseMessage,
                                    boolean requestedByExternalArea, Instant updatedAt) {
        informationProvided(ticket, responseMessage, List.of(), ActorType.CITIZEN, null,
                requestedByExternalArea, updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void informationProvided(Ticket ticket, String responseMessage, List<Attachment> attachments,
                                    ActorType actorType, String actorId,
                                    boolean requestedByExternalArea, Instant updatedAt) {
        if (ticket.isAnonymous() && (!requestedByExternalArea || isSelfManaged(ticket))) {
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", responseMessage);
        Map<String, Object> updatedBy = new LinkedHashMap<>();
        updatedBy.put("type", actorType.name());
        updatedBy.put("id", actorId);
        saveUpdated(ticket, TicketUpdatedType.INFORMATION_PROVIDED,
                "El ciudadano aportó la información solicitada.",
                Map.of("informationResponse", response), eventAttachments(attachments), updatedBy, updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolved(Ticket ticket, ResolutionType type, String publicMessage, Instant updatedAt) {
        if (ticket.isAnonymous()) {
            return;
        }
        saveUpdated(ticket, TicketUpdatedType.RESOLVED, publicMessage,
                Map.of("resolution", Map.of("type", type.name())), List.of(), updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reopened(Ticket ticket, String reason, Instant updatedAt) {
        if (ticket.isAnonymous() && isSelfManaged(ticket)) {
            return;
        }
        Map<String, Object> reopen = new LinkedHashMap<>();
        reopen.put("reason", reason);
        saveUpdated(ticket, TicketUpdatedType.REOPENED, reason,
                Map.of("reopen", reopen), List.of(), updatedAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(Ticket ticket, CancellationReasonCode reasonCode, String publicMessage,
                          boolean originatedByM2, Instant updatedAt) {
        if (ticket.isAnonymous() && (!originatedByM2 || isSelfManaged(ticket))) {
            return;
        }
        saveUpdated(ticket, TicketUpdatedType.CANCELLED, publicMessage,
                Map.of("cancellation", Map.of("reasonCode", reasonCode.name())), List.of(), updatedAt);
    }

    private void saveUpdated(Ticket ticket, TicketUpdatedType updateType, String publicMessage,
                             Map<String, Object> details, List<Map<String, Object>> attachments,
                             Instant updatedAt) {
        saveUpdated(ticket, updateType, publicMessage, details, attachments, null, updatedAt);
    }

    private void saveUpdated(Ticket ticket, TicketUpdatedType updateType, String publicMessage,
                             Map<String, Object> details, List<Map<String, Object>> attachments,
                             Map<String, Object> updatedBy, Instant updatedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("publicId", ticket.getPublicId());
        data.put("citizenId", ticket.getCitizenId());
        data.put("isAnonymous", ticket.isAnonymous());
        data.put("responsibleAreaId", ticket.getResponsibleAreaId());
        data.put("updateType", updateType.name());
        data.put("currentStatus", ticket.getCurrentStatus().name());
        data.put("currentPriority", ticket.getCurrentPriority().name());
        data.put("progress", ticket.getCurrentProgress());
        data.put("publicMessage", publicMessage);
        data.put("details", details);
        data.put("attachments", attachments);
        if (updatedBy != null) {
            data.put("updatedBy", updatedBy);
        }
        data.put("updatedAt", updatedAt.toString());
        save(ticket, "ticketUpdated", updateType, data);
    }

    private void save(Ticket ticket, String eventType, TicketUpdatedType updateType,
                      Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("moduleId", producerModuleId);
        producer.put("service", producerService);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("specVersion", "1.0");
        payload.put("eventId", eventId.toString());
        payload.put("eventType", eventType);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("producer", producer);
        payload.put("subject", "tickets/" + ticket.getId());
        payload.put("data", data);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setUpdateType(updateType);
        event.setTicket(ticket);
        event.setPayload(payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxEventRepository.save(event);
    }

    private boolean isSelfManaged(Ticket ticket) {
        return MODULE_ID.equalsIgnoreCase(ticket.getResponsibleAreaId());
    }

    private Map<String, Object> eventLocation(TicketLocation location) {
        if (location == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("addressLine", location.getAddressLine());
        result.put("street", location.getStreet());
        result.put("streetNumber", location.getStreetNumber());
        result.put("neighborhoodId", location.getNeighborhood() == null ? null : location.getNeighborhood().getId());
        result.put("reference", location.getReference());
        return result;
    }

    private List<Map<String, Object>> eventAttachments(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream().map(attachment -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileName", attachment.getFileName());
            result.put("contentType", attachment.getContentType());
            result.put("url", attachment.getStorageKey());
            result.put("sizeBytes", attachment.getSizeBytes());
            return result;
        }).toList();
    }

    private Map<String, Object> eventEscalation(Ticket ticket) {
        Map<String, Object> escalation = new LinkedHashMap<>();
        escalation.put("active", ticket.isEscalated());
        escalation.put("reasonCode", ticket.getEscalationReasonCode() == null
                ? null : ticket.getEscalationReasonCode().name());
        escalation.put("escalatedAt", instant(ticket.getEscalatedAt()));
        return escalation;
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
