package com.reclamos.backend.openapi;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TicketCreationIdempotencyOpenApiTest {
    @Test
    @SuppressWarnings("unchecked")
    void creationDocumentsOptionalKeyBothMediaTypesConflictAndAnonymousCredentialSuppression() throws Exception {
        try (var input = getClass().getResourceAsStream("/static/openapi.yaml")) {
            Map<String, Object> api = new Yaml().load(input);
            var paths = (Map<String, Map<String, Object>>) api.get("paths");
            var create = (Map<String, Object>) paths.get("/api/tickets").get("post");
            var key = ((List<Map<String, Object>>) create.get("parameters")).stream()
                    .filter(p -> "Idempotency-Key".equals(p.get("name"))).findFirst().orElseThrow();
            assertThat(key).containsEntry("in", "header").containsEntry("required", false);
            assertThat((Map<String, Object>) key.get("schema"))
                    .containsEntry("minLength", 1).containsEntry("maxLength", 200);
            assertThat((Map<String, Object>) ((Map<String, Object>) create.get("requestBody")).get("content"))
                    .containsKeys("application/json", "multipart/form-data");
            var responses = (Map<String, Object>) create.get("responses");
            assertThat(responses).containsKeys("201", "400", "409");
            assertThat(((Map<String, Object>) responses.get("409")).get("description").toString())
                    .contains("IDEMPOTENCY_KEY_CONFLICT");
            assertThat(create.get("description").toString()).contains("sin vencimiento", "credenciales anónimas", "citizenId");
            var components = (Map<String, Object>) api.get("components");
            var schemas = (Map<String, Object>) components.get("schemas");
            var properties = (Map<String, Object>) ((Map<String, Object>) schemas.get("CreateTicketResponse")).get("properties");
            assertThat((Map<String, Object>) properties.get("trackingCode")).containsEntry("nullable", true);
        }
    }
}
