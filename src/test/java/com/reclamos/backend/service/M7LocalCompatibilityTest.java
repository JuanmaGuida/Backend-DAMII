package com.reclamos.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reclamos.backend.inbox.CoreIncomingEventValidator;
import com.reclamos.backend.inbox.IncomingEventFailure;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Synthetic M2 examples only: no M7 contract or bilateral approval is asserted. */
class M7LocalCompatibilityTest {
    private final Path examples = Path.of("docs/integrations/examples/m7");
    private final ObjectMapper json = new ObjectMapper();
    private final CoreIncomingEventValidator validator = new CoreIncomingEventValidator();

    @ParameterizedTest @ValueSource(strings={"routed-local.json","routed-anonymous-local.json"})
    void localRoutingExamplesMatchFullM2Schema(String file) throws Exception {
        Map<String,Object> root = json.readValue(examples.resolve(file).toFile(), new TypeReference<>() {});
        EventEnvelopeContractAssertions.assertValid(root);
        EventEnvelopeContractAssertions.assertFullEventValid(root);
        var data = (Map<?,?>) root.get("data");
        assertThat(data.get("responsibleAreaId")).isEqualTo("M7");
        assertThat(data.get("updateType")).isEqualTo("ROUTED");
        assertThat(data.get("isAnonymous")).isEqualTo(file.contains("anonymous"));
    }

    @ParameterizedTest @ValueSource(strings={"started","progress","information-required","returned","resolved","rejected"})
    void sixPotentialIncomingVariantsDecodeUsingExistingM2Contract(String type) throws Exception {
        var event = validator.decode(Files.readAllBytes(examples.resolve(type+"-local.json")));
        assertThat(event.sourceModule()).isEqualTo("transito");
        assertThat(event.eventType()).isEqualTo("updateTicketStatus");
        var request = validator.statusRequest(event);
        assertThat(request.updateType().name()).isEqualTo(type.toUpperCase(java.util.Locale.ROOT).replace('-','_'));
        assertThat(request.updatedBy().type()).isEqualTo("SYSTEM");
        assertThat(request.updatedBy().id()).isNull();
        if (type.equals("progress")) {
            assertThat(request.progress()).isNull();
            assertThat(request.publicMessage()).isNull();
            assertThat(request.attachments()).hasSize(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"unknownField","ticketId","eventId","date","actor","version","sourceModule","missingActor","missingDetails"})
    void invalidLocalExamplesAreRejectedBeforeBusinessProcessing(String mutation) throws Exception {
        ObjectNode root = (ObjectNode) json.readTree(examples.resolve("resolved-local.json").toFile());
        ObjectNode data = (ObjectNode) root.get("data");
        switch (mutation) {
            case "unknownField" -> data.put("officialPriority","HIGH");
            case "ticketId" -> data.put("ticketId","local-case-number");
            case "eventId" -> root.put("eventId","not-a-uuid");
            case "date" -> data.put("updateOccurredAt","yesterday");
            case "actor" -> ((ObjectNode) data.get("updatedBy")).put("type","ADMIN");
            case "version" -> root.put("eventVersion","2.0");
            case "sourceModule" -> root.put("sourceModule","M7");
            case "missingActor" -> data.remove("updatedBy");
            case "missingDetails" -> data.remove("details");
        }
        assertThatThrownBy(() -> validator.decode(json.writeValueAsBytes(root))).isInstanceOf(IncomingEventFailure.class);
    }

    @ParameterizedTest @ValueSource(strings={"omitted","null"})
    void optionalTraceRepresentationRetainsExistingHashNormalization(String representation) throws Exception {
        ObjectNode root = (ObjectNode) json.readTree(examples.resolve("started-local.json").toFile());
        root.putNull("correlationId").putNull("causationId");
        String hash = validator.decode(json.writeValueAsBytes(root)).hash();
        if (representation.equals("omitted")) {
            root.remove("correlationId"); root.remove("causationId"); root.remove("eventVersion");
        }
        assertThat(validator.decode(json.writeValueAsBytes(root)).hash()).isEqualTo(hash);
    }
}
