package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reclamos.backend.inbox.CoreIncomingEventValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** PDF M4 v1.2 RC examples; local validation does not certify bilateral delivery. */
class M4DocumentationContractTest {
    private final Path examples=Path.of("docs/integrations/examples/m4");
    private final ObjectMapper json=new ObjectMapper();
    private final CoreIncomingEventValidator validator=new CoreIncomingEventValidator();

    @Test void originalM4ExamplesAndLocalAdaptationMatchExistingSchemas() throws Exception {
        Map<String,Object> routed=json.readValue(examples.resolve("routed-original.json").toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        EventEnvelopeContractAssertions.assertFullEventValid(routed);
        assertThat((Map<?,?>)routed.get("data")).extracting(m -> m.get("responsibleAreaId")).isEqualTo("M4");
        var event=validator.decode(Files.readAllBytes(examples.resolve("resolved-original.json")));
        assertThat(event.sourceModule()).isEqualTo("habilitaciones");
        assertThat(event.eventType()).isEqualTo("updateTicketStatus");
        var request=validator.statusRequest(event);
        assertThat(request.updateType().name()).isEqualTo("RESOLVED");
        assertThat(request.attachments()).isEmpty();
        Map<String,Object> adapted=json.readValue(examples.resolve("routed-adaptado.json").toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        EventEnvelopeContractAssertions.assertFullEventValid(adapted);
        assertThat(request.updatedBy().type()).isEqualTo("EXTERNAL_USER");
    }

    @ParameterizedTest @ValueSource(strings={"unknownField","ticketId","updateOccurredAt","actor","version"})
    void invalidM4FactsAreRejectedBeforeBusinessProcessing(String field) throws Exception {
        ObjectNode root=(ObjectNode)json.readTree(examples.resolve("resolved-original.json").toFile());
        ObjectNode data=(ObjectNode)root.get("data");
        switch(field) {
            case "unknownField" -> data.put("officialPriority","HIGH");
            case "ticketId" -> data.put("ticketId","commercial-file-123");
            case "updateOccurredAt" -> data.put("updateOccurredAt","yesterday");
            case "actor" -> ((ObjectNode)data.get("updatedBy")).put("id","not-a-uuid");
            case "version" -> root.put("eventVersion","2.0");
        }
        assertThatThrownBy(() -> validator.decode(json.writeValueAsBytes(root)))
                .isInstanceOf(com.reclamos.backend.inbox.IncomingEventFailure.class);
    }
}
