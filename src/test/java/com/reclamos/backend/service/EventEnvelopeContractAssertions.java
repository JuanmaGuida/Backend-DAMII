package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Original schemas, with format validation enabled; envelope and full data are separate checks. */
final class EventEnvelopeContractAssertions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "https://municipalidad-uade.example/m2/events/v1.76/";
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(BASE, "classpath:events/v1.76/")));

    static JsonSchema schema(String name) {
        return FACTORY.getSchema(SchemaLocation.of(BASE + name),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    static void assertValid(Map<String, Object> payload) {
        assertThat(schema("envelope.schema.json").validate(JSON.valueToTree(payload))).isEmpty();
        assertThat(payload).containsOnlyKeys("eventId", "eventType", "eventVersion", "occurredAt",
                "sourceModule", "correlationId", "causationId", "data");
        assertThat(payload).containsEntry("sourceModule", "atencion-ciudadana").containsEntry("eventVersion", "1.0");
    }

    static void assertFullEventValid(Map<String, Object> payload) {
        assertThat(schema(payload.get("eventType") + ".schema.json").validate(JSON.valueToTree(payload))).isEmpty();
    }

    static void assertFullEventInvalid(Map<String, Object> payload) {
        assertThat(schema(payload.get("eventType") + ".schema.json").validate(JSON.valueToTree(payload))).isNotEmpty();
    }

    private EventEnvelopeContractAssertions() { }
}
