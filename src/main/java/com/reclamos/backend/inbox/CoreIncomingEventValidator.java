package com.reclamos.backend.inbox;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static com.reclamos.backend.inbox.IncomingEventFailure.Code.*;

@Component
public final class CoreIncomingEventValidator {
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final JsonSchema envelope;
    private final JsonSchema update;

    public CoreIncomingEventValidator() {
        String base = "https://municipalidad-uade.example/m2/events/v1.78/";
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(base, "classpath:events/v1.78/")));
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        envelope = factory.getSchema(SchemaLocation.of(base + "envelope.schema.json"), config);
        update = factory.getSchema(SchemaLocation.of(base + "updateTicketStatus.schema.json"), config);
    }

    public CoreIncomingEvent decode(byte[] body) {
        try {
            if (body == null || body.length == 0 || body.length > 1048576) throw invalid();
            JsonNode root = json.readTree(body);
            if (!envelope.validate(root).isEmpty()) throw invalid();
            if (root.has("eventVersion") && !"1.0".equals(root.get("eventVersion").asText()))
                throw new IncomingEventFailure(UNSUPPORTED_VERSION);
            String type = root.get("eventType").asText();
            if (type.equals("updateTicketStatus") && !update.validate(root).isEmpty()) throw invalid();
            if (type.equals("citizenUpdated")) {
                if (!"ciudadanos".equals(root.get("sourceModule").asText())) throw new IncomingEventFailure(WRONG_PRODUCER);
                validateCitizen(root.get("data"));
            }
            var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) root.deepCopy();
            if (!normalized.has("eventVersion")) normalized.put("eventVersion", "1.0");
            for (String optional : List.of("correlationId", "causationId")) {
                if (!normalized.has(optional)) normalized.putNull(optional);
            }
            byte[] canonical = json.writeValueAsBytes(json.convertValue(normalized, Map.class));
            return new CoreIncomingEvent(uuid(root.get("eventId")), type, root.get("sourceModule").asText(),
                    Instant.parse(root.get("occurredAt").asText()), optionalUuid(root.get("correlationId")),
                    optionalUuid(root.get("causationId")), root.get("data"),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
        } catch (IncomingEventFailure failure) { throw failure; }
        catch (Exception invalid) { throw invalid(); }
    }

    public UpdateTicketStatusRequest statusRequest(CoreIncomingEvent event) {
        try {
            return json.treeToValue(event.data(), UpdateTicketStatusRequest.class);
        } catch (Exception failure) { throw invalid(); }
    }

    private void validateCitizen(JsonNode data) {
        if (!data.isObject() || !Set.of("REGISTERED", "BLOCKED", "DECEASED", "ADDRESS_UPDATED")
                .contains(data.path("updateType").asText()) || !data.path("details").isObject()) throw invalid();
        uuid(data.get("citizenId"));
        JsonNode details = data.get("details");
        for (String field : List.of("name", "lastname")) {
            JsonNode value = details.get(field);
            if (value != null && !value.isNull() && (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 100)) throw invalid();
        }
        for (String field : List.of("previousAddress", "newAddress")) {
            if (details.hasNonNull(field) && !details.get(field).isObject()) throw invalid();
        }
        if (data.has("updatedBy") && (!data.get("updatedBy").isObject() || !data.get("updatedBy").path("type").isTextual())) throw invalid();
        // M1 v3.1 supplies DNI/CUIL/address/actor metadata too; none is projected or stored in Inbox.
    }

    public static UUID uuid(JsonNode value) {
        if (value == null || !value.isTextual() || !value.asText().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString(value.asText());
    }
    private static UUID optionalUuid(JsonNode value) { return value == null || value.isNull() ? null : uuid(value); }
    private static IncomingEventFailure invalid() { return new IncomingEventFailure(INVALID_EVENT); }
}
