package com.reclamos.backend.inbox;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static com.reclamos.backend.inbox.IncomingFixtures.*;
import static org.assertj.core.api.Assertions.*;

class CoreIncomingEventValidatorTest {
    private final CoreIncomingEventValidator validator = new CoreIncomingEventValidator();

    @ParameterizedTest @ValueSource(strings={"STARTED","PROGRESS","INFORMATION_REQUIRED","RETURNED","RESOLVED","REJECTED"})
    void acceptsOfficialVariantsAndAdaptsDatesWithoutLegacyEnvelope(String type) {
        UUID ticket = UUID.randomUUID();
        ObjectNode event = status(ticket, type);
        var parsed = validator.decode(bytes(event));
        var request = validator.statusRequest(parsed);
        assertThat(request.ticketId()).isEqualTo(ticket);
        assertThat(request.updateType().name()).isEqualTo(type);
        assertThat(request.updateOccurredAt()).isEqualTo(Instant.parse(TIME));
        assertThat(parsed.toString()).doesNotContain("Trabajo terminado", "Corresponde reclasificar");
    }
    @ParameterizedTest @ValueSource(strings={"REGISTERED","BLOCKED","DECEASED","ADDRESS_UPDATED"})
    void acceptsM1VariantsIncludingNumericAreaActorAndPartialProfiles(String type) {
        var event = citizen(UUID.randomUUID(),type);
        details(event).put("dni","synthetic-dni").put("cuil","synthetic-cuil");
        assertThat(validator.decode(bytes(event)).eventType()).isEqualTo("citizenUpdated");
    }
    @Test void normalizesOptionalEnvelopeFieldsAndObjectKeyOrderingForDeduplication() {
        var event = citizen(UUID.randomUUID(),"REGISTERED");
        String hash = validator.decode(bytes(event)).hash();
        event.remove("eventVersion"); event.putNull("correlationId"); event.putNull("causationId");
        var reversed = JSON.createObjectNode(); event.properties().stream().toList().reversed().forEach(e -> reversed.set(e.getKey(),e.getValue()));
        assertThat(validator.decode(bytes(reversed)).hash()).isEqualTo(hash);
    }
    @Test void acceptsOffsetsAndNullableActorIdAndTraces() {
        var event = status(UUID.randomUUID(),"STARTED");
        event.put("occurredAt","2026-10-01T09:00:00-03:00").putNull("correlationId").putNull("causationId");
        ((ObjectNode)data(event).get("updatedBy")).putNull("id");
        assertThat(validator.decode(bytes(event)).occurredAt()).isEqualTo(Instant.parse(TIME));
    }
    @Test void rejectsUnexpectedVersionsAndWrongCitizenProducer() {
        var event = citizen(UUID.randomUUID(),"BLOCKED");
        event.put("eventVersion","2.0"); rejected(event,IncomingEventFailure.Code.UNSUPPORTED_VERSION);
        event.put("eventVersion","1.0").put("sourceModule","obras"); rejected(event,IncomingEventFailure.Code.WRONG_PRODUCER);
    }
    @ParameterizedTest @ValueSource(strings={"citizenId","updateType","details"})
    void rejectsMissingCitizenRequiredStructure(String key) {
        var event = citizen(UUID.randomUUID(),"REGISTERED"); data(event).remove(key);
        rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
    }
    @Test void rejectsNonCanonicalIdentitiesAndMalformedDetails() {
        var event = citizen(UUID.randomUUID(),"REGISTERED");
        data(event).put("citizenId","14"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
        data(event).put("citizenId",UUID.randomUUID().toString());
        details(event).put("name",""); rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
        details(event).remove("name"); details(event).put("newAddress","text"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
        details(event).remove("newAddress"); data(event).put("updatedBy","text"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
    }
    @Test void rejectsLegacyExtraFieldsUnknownBusinessFieldsAndInternalActors() {
        var event = status(UUID.randomUUID(),"STARTED");
        event.put("specVersion","1.0"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT); event.remove("specVersion");
        data(event).put("newStatus","CLOSED"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT); data(event).remove("newStatus");
        ((ObjectNode)data(event).get("updatedBy")).put("type","ADMIN"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
    }
    @Test void rejectsDuplicateJsonKeysTrailingContentInvalidJsonAndOversizedBodies() {
        String valid = new String(bytes(citizen(UUID.randomUUID(),"REGISTERED")),StandardCharsets.UTF_8);
        for (byte[] body : new byte[][]{null,new byte[0],new byte[1048577],"{".getBytes(),(valid+" {}").getBytes(),
                valid.replace("\"eventVersion\":\"1.0\"","\"eventVersion\":\"1.0\",\"eventVersion\":\"1.0\"").getBytes()}) {
            assertThatThrownBy(() -> validator.decode(body)).isInstanceOf(IncomingEventFailure.class).hasMessage("INVALID_EVENT");
        }
    }
    @Test void validatesUnknownEventsEnvelopeButLeavesTheirDataToTheirConsumers() {
        var event = envelope("otherEvent","obras",JSON.createObjectNode().put("value",1));
        assertThat(validator.decode(bytes(event)).eventType()).isEqualTo("otherEvent");
        event.put("eventId","not-a-uuid"); rejected(event,IncomingEventFailure.Code.INVALID_EVENT);
    }
    private void rejected(ObjectNode event, IncomingEventFailure.Code code) {
        assertThatThrownBy(() -> validator.decode(bytes(event))).isInstanceOf(IncomingEventFailure.class).hasMessage(code.name());
    }
}
