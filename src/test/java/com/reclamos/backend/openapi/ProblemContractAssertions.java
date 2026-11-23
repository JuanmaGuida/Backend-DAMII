package com.reclamos.backend.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import io.swagger.v3.core.util.Yaml;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public final class ProblemContractAssertions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SPEC = loadSpec();
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    private ProblemContractAssertions() {
    }

    public static ResultMatcher matchesProblem(String contractPath, int expectedStatus) {
        return result -> {
            var response = result.getResponse();
            assertProblem(result.getRequest().getMethod(), contractPath, expectedStatus,
                    response.getStatus(), response.getContentType(), response.getContentAsString());
        };
    }

    public static void assertProblem(String method, String contractPath, int expectedStatus,
                                     int status, String contentType, String body) throws IOException {
        String scenario = method + " " + contractPath + " " + expectedStatus;
        assertThat(status).as(scenario).isEqualTo(expectedStatus);
        assertThat(contentType).as("Content-Type for %s", scenario).isNotNull();
        assertThat(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON))
                .as("Problem Details Content-Type for %s", scenario).isTrue();

        var response = resolve(SPEC.path("paths").path(contractPath).path(method.toLowerCase(Locale.ROOT))
                .path("responses").path(Integer.toString(expectedStatus)));
        var schema = resolve(response.path("content").path(MediaType.APPLICATION_PROBLEM_JSON_VALUE).path("schema"));
        assertThat(schema.isObject()).as("Published Problem Details schema for %s", scenario).isTrue();
        var problem = JSON.readTree(body);
        var validator = SCHEMAS.getSchema(schema, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        assertThat(validator.validate(problem)).as("OpenAPI schema violations for %s", scenario).isEmpty();
        assertThat(problem.path("status").intValue()).as("Body status for %s", scenario).isEqualTo(status);
    }

    private static JsonNode resolve(JsonNode node) {
        if (node.has("$ref")) {
            return SPEC.at(node.path("$ref").asText().substring(1));
        }
        return node;
    }

    private static JsonNode loadSpec() {
        try (var yaml = Objects.requireNonNull(ProblemContractAssertions.class.getResourceAsStream("/static/openapi.yaml"))) {
            return Yaml.mapper().readTree(yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
