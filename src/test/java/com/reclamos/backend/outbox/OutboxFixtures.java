package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.identity.ModuleRole;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

final class OutboxFixtures {
    static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    static CoreOutboxSettings settings(Set<UUID> approved) {
        return new CoreOutboxSettings("rabbit.example.test", 5671, "core", "synthetic-module-user", "synthetic-password",
                String.join(",", approved.stream().map(UUID::toString).toList()), 3, Duration.ofSeconds(15),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }
    static OutboxEvent event(String type) {
        Ticket ticket = new Ticket(); ticket.setId(UUID.randomUUID()); ticket.setPublicId("TK-2026-000123");
        ticket.setAnonymous(false); ticket.setCitizenId(UUID.randomUUID());
        ticket.setCreatedAt(NOW); ticket.setResponsibleAreaId("M6");
        OutboxEvent event = new OutboxEvent(); event.setId(1L); event.setEventId(UUID.randomUUID());
        event.setEventType(type); event.setTicket(ticket); event.setCreatedAt(NOW);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId().toString()); data.put("publicId", ticket.getPublicId());
        data.put("citizenId", ticket.getCitizenId().toString()); data.put("isAnonymous", false);
        data.put("responsibleAreaId", "M6");
        if ("ticketCreated".equals(type)) {
            data.put("requestType", "Luminaria"); data.put("category", "Servicios"); data.put("subcategory", "Alumbrado");
            data.put("ticketType", "COMPLAINT"); data.put("summary", "Luminaria apagada"); data.put("description", "Descripción pública");
            data.put("status", "REGISTERED"); data.put("priority", "MEDIUM"); data.put("createdAt", NOW.toString());
            data.put("formData", new LinkedHashMap<>());
        } else {
            event.setUpdateType(TicketUpdatedType.STATUS_CHANGED);
            data.put("updateType", "STATUS_CHANGED"); data.put("currentStatus", "IN_REVIEW");
            data.put("currentPriority", "MEDIUM"); data.put("updatedAt", NOW.toString()); data.put("details", null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getEventId().toString()); payload.put("eventType", type); payload.put("eventVersion", "1.0");
        payload.put("occurredAt", NOW.toString()); payload.put("sourceModule", "atencion-ciudadana");
        payload.put("correlationId", UUID.randomUUID().toString()); payload.put("causationId", null); payload.put("data", data);
        event.setPayload(payload); return event;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> data(OutboxEvent event) { return (Map<String, Object>) event.getPayload().get("data"); }
    static ModuleUser realCitizen(OutboxEvent event) {
        ModuleUser user = new ModuleUser(); user.setActorId(event.getTicket().getCitizenId());
        user.setCitizenId(event.getTicket().getCitizenId()); user.setActorType(IdentityActorType.CIUDADANO);
        user.setIdentitySource(IdentitySource.M1); user.setRole(ModuleRole.CITIZEN);
        user.setFirstName("Synthetic"); user.setLastName("Citizen"); user.setLastSyncedAt(NOW); return user;
    }
    private OutboxFixtures() { }
}
