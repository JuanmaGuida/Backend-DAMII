package com.reclamos.backend.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

final class IncomingFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TIME = "2026-10-01T12:00:00Z";
    static ObjectNode envelope(String type, String source, ObjectNode data) {
        ObjectNode root = JSON.createObjectNode();
        root.put("eventId", UUID.randomUUID().toString()).put("eventType", type).put("eventVersion", "1.0")
                .put("sourceModule", source).put("occurredAt", TIME);
        root.set("data", data); return root;
    }
    static ObjectNode status(UUID ticket, String type) {
        ObjectNode data = JSON.createObjectNode();
        data.put("ticketId", ticket.toString()).put("updateType", type).put("updateOccurredAt", TIME);
        data.putObject("updatedBy").put("type", "EXTERNAL_USER").put("id", UUID.randomUUID().toString());
        switch (type) {
            case "PROGRESS" -> data.put("progress", 30);
            case "INFORMATION_REQUIRED" -> data.putObject("details").putObject("informationRequest").put("messageForCitizen", "Indique el número");
            case "RESOLVED" -> { data.put("publicMessage", "Trabajo terminado"); data.putObject("details").putObject("resolution").put("type", "ACTION_COMPLETED"); }
            case "RETURNED" -> { data.put("internalMessage", "Corresponde reclasificar"); data.putObject("details").putObject("returnInfo").put("reasonCode", "OTHER"); }
            case "REJECTED" -> { data.put("publicMessage", "Fuera de alcance"); data.putObject("details").putObject("cancellation").put("reasonCode", "OUT_OF_SCOPE"); }
        }
        return envelope("updateTicketStatus", "ambiente", data);
    }
    static ObjectNode citizen(UUID citizen, String type) {
        ObjectNode data = JSON.createObjectNode();
        data.put("citizenId", citizen.toString()).put("updateType", type).putObject("details");
        data.putObject("updatedBy").put("type", "AREA_USER").put("id", 14);
        return envelope("citizenUpdated", "ciudadanos", data);
    }
    static ObjectNode data(ObjectNode event) { return (ObjectNode) event.get("data"); }
    static ObjectNode details(ObjectNode event) { return (ObjectNode) data(event).get("details"); }
    static byte[] bytes(ObjectNode event) {
        try { return JSON.writeValueAsBytes(event); } catch (Exception e) { throw new AssertionError(e); }
    }
    private IncomingFixtures() { }
}
