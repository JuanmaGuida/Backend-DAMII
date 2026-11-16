package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reclamos.backend.inbox.CoreIncomingEventValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

/** PDF layout transcripts and editorial normalization are separate artifacts, never bilateral evidence. */
class M8DocumentationContractTest {
    private final Path examples = Path.of("docs/integrations/examples/m8");
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"started,true", "progress,false", "information-required,true", "returned,false",
            "resolved-fulfilled,false", "resolved-no-further,true", "rejected,true", "routed,true"})
    void originalJsonParseabilityIsRecordedWithoutSilentlyRepairingStrings(String name, boolean parseable) throws Exception {
        String raw = Files.readString(examples.resolve(name + "-original.txt"));
        if (parseable) {
            var original = json.readTree(raw);
            assertThat(EventEnvelopeContractAssertions.schema(original.get("eventType").asText()+".schema.json")
                    .validate(original)).isEmpty();
            assertThat(original).isEqualTo(json.readTree(examples.resolve(name+"-normalizado.json").toFile()));
        } else {
            assertThatThrownBy(() -> json.readTree(raw)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"started","progress","information-required","returned","resolved-fulfilled",
            "resolved-no-further","rejected","routed"})
    void editoriallyNormalizedExamplesMatchFullSchemasWithoutValueSubstitution(String name) throws Exception {
        var node = json.readTree(examples.resolve(name+"-normalizado.json").toFile());
        assertThat(EventEnvelopeContractAssertions.schema(node.get("eventType").asText()+".schema.json").validate(node)).isEmpty();
        if (name.equals("routed")) {
            assertThat(node.path("data").path("responsibleAreaId").asText()).isEqualTo("M8");
        } else {
            var validator = new CoreIncomingEventValidator();
            var event = validator.decode(Files.readAllBytes(examples.resolve(name+"-normalizado.json")));
            assertThat(event.sourceModule()).isEqualTo("desarrollo-social");
            assertThat(validator.statusRequest(event).updatedBy().id()).isNull();
            var routed = json.readTree(examples.resolve("routed-normalizado.json").toFile());
            assertThat(node.get("correlationId")).isEqualTo(routed.get("correlationId"));
            assertThat(node.get("causationId")).isEqualTo(routed.get("eventId"));
            assertThat(node.path("data").get("ticketId")).isEqualTo(routed.path("data").get("ticketId"));
        }
    }
}
