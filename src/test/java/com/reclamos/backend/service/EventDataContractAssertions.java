package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates only data; full Core envelope alignment belongs to H01. */
final class EventDataContractAssertions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(
                    "https://municipalidad-uade.example/m2/events/v1.76/", "classpath:events/v1.76/")))
            .getSchema(SchemaLocation.of(
                    "https://municipalidad-uade.example/m2/events/v1.76/ticketUpdated.data.schema.json"),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());

    static void assertValid(Object data) {
        assertThat(SCHEMA.validate(JSON.valueToTree(data))).isEmpty();
    }

    static void assertInvalid(Object data) {
        assertThat(SCHEMA.validate(JSON.valueToTree(data))).isNotEmpty();
    }

    private EventDataContractAssertions() { }
}
