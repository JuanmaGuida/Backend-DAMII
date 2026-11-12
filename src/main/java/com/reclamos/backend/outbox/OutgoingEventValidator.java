package com.reclamos.backend.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import com.reclamos.backend.entity.OutboxEvent;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.reclamos.backend.outbox.PublicationFailure.Code.*;

@Component
@ConditionalOnProperty(name = "app.core.outbox.enabled", havingValue = "true")
public final class OutgoingEventValidator {
    private static final String BASE = "https://municipalidad-uade.example/m2/events/v1.78/";
    private static final Set<String> ROOT = Set.of("eventId", "eventType", "eventVersion", "occurredAt",
            "sourceModule", "correlationId", "causationId", "data");
    // Reserved technical fields must never enter a public event, including inside formData.
    // Generic question codes (session, cookie, password, token, etc.) are not credential metadata.
    // Closed schemas protect the envelope/data structure; formData holds public dynamic answers.
    private static final Set<String> RESERVED_PRIVATE_FIELDS = Set.of("internalmessage", "trackingcode", "trackingaccesscode",
            "trackingcodehash", "trackingcodeciphertext", "trackingcodenonce", "trackingcodekeyid",
            "anonymousaccesspassword", "anonymousaccesspasswordhash", "anonymouscontactchannel", "anonymouscontactvalue",
            "accesstoken", "refreshtoken", "ticketactivity", "ticketactivities");
    private static final Set<String> ANONYMOUS_UPDATES = Set.of("ROUTED", "INFORMATION_PROVIDED", "REOPENED", "CLOSED",
            "CANCELLED", "ESCALATION_CHANGED");
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonSchema> schemas;
    private final ModuleUserRepository users;

    public OutgoingEventValidator(ModuleUserRepository users) {
        this.users = users;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(BASE, "classpath:events/v1.78/")));
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        schemas = Map.of("ticketCreated", factory.getSchema(SchemaLocation.of(BASE + "ticketCreated.schema.json"), config),
                "ticketUpdated", factory.getSchema(SchemaLocation.of(BASE + "ticketUpdated.schema.json"), config));
    }

    public byte[] prepare(OutboxEvent event) {
        try {
            JsonSchema schema = schemas.get(event.getEventType());
            if (schema == null || event.getPayload() == null || !event.getPayload().keySet().equals(ROOT)) throw invalid();
            JsonNode root = json.valueToTree(event.getPayload());
            if (!schema.validate(root).isEmpty() || sensitive(root)) throw invalid();
            JsonNode data = root.get("data");
            var ticket = event.getTicket();
            if (!event.getEventId().equals(UUID.fromString(root.get("eventId").asText()))
                    || !event.getEventType().equals(root.get("eventType").asText())
                    || !ticket.getId().equals(UUID.fromString(data.get("ticketId").asText()))
                    || !ticket.getPublicId().equals(data.get("publicId").asText())
                    || ticket.isAnonymous() != data.get("isAnonymous").asBoolean()) throw invalid();
            if ("ticketUpdated".equals(event.getEventType()) && (event.getUpdateType() == null
                    || !event.getUpdateType().name().equals(data.get("updateType").asText()))) throw invalid();
            if ("ticketCreated".equals(event.getEventType()) && event.getUpdateType() != null) throw invalid();
            if (ticket.isAnonymous()) {
                if (!data.get("citizenId").isNull() || ticket.getCitizenId() != null
                        || !ANONYMOUS_UPDATES.contains(data.path("updateType").asText())
                        || "M2".equals(data.path("responsibleAreaId").asText())) throw invalid();
            } else {
                UUID citizen = UUID.fromString(data.get("citizenId").asText());
                if (!citizen.equals(ticket.getCitizenId())) throw invalid();
                // Explicit approval never turns a synthetic UUID into a real M1 identity.
                boolean verified = users.findByCitizenId(citizen).filter(user -> user.getIdentitySource() == IdentitySource.M1
                        && user.getActorType() == IdentityActorType.CIUDADANO && citizen.equals(user.getActorId())).isPresent();
                if (!verified) throw new PublicationFailure(UNVERIFIED_IDENTITY);
            }
            byte[] body = json.writeValueAsBytes(root);
            if (body.length > 1048576) throw invalid();
            return body;
        } catch (PublicationFailure failure) { throw failure; }
        catch (org.springframework.dao.DataAccessException unavailable) { throw unavailable; }
        catch (Exception failure) { throw invalid(); }
    }

    private static boolean sensitive(JsonNode value) {
        if (value.isObject()) {
            for (var property : value.properties()) {
                String key = property.getKey().replaceAll("[-_]", "").toLowerCase(java.util.Locale.ROOT);
                if (RESERVED_PRIVATE_FIELDS.contains(key) || sensitive(property.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) if (sensitive(item)) return true;
        }
        return false;
    }
    private static PublicationFailure invalid() { return new PublicationFailure(INVALID_PAYLOAD); }
}
