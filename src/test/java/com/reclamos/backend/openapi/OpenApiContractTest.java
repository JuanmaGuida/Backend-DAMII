package com.reclamos.backend.openapi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "options", "head", "trace");
    private static final Set<String> EXPECTED_OPERATIONS = Set.of(
            "POST /api/auth/login",
            "GET /api/auth/me",
            "GET /api/catalog/categories",
            "GET /api/catalog/neighborhoods",
            "GET /api/catalog/neighborhoods/{neighborhoodId}",
            "GET /api/catalog/categories/{categoryId}/subcategories",
            "GET /api/catalog/subcategories/{subcategoryId}/request-types",
            "GET /api/catalog/request-types/{requestTypeId}/form",
            "GET /api/me/tickets",
            "GET /api/tickets",
            "POST /api/tickets",
            "GET /api/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}/citizen-view",
            "POST /api/tickets/{ticketId}/review",
            "PATCH /api/tickets/{ticketId}/classification",
            "POST /api/tickets/{ticketId}/route",
            "POST /api/tickets/{ticketId}/information-request",
            "POST /api/tickets/{ticketId}/information-response",
            "POST /api/tickets/{ticketId}/resolution",
            "POST /api/tickets/{ticketId}/resolution/confirm",
            "POST /api/tickets/{ticketId}/resolution/reopen",
            "POST /api/tracking/access"
    );
    private static final Set<String> PROTECTED_OPERATIONS = Set.of(
            "GET /api/auth/me",
            "GET /api/me/tickets",
            "GET /api/tickets",
            "POST /api/tickets",
            "GET /api/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}/citizen-view",
            "POST /api/tickets/{ticketId}/review",
            "PATCH /api/tickets/{ticketId}/classification",
            "POST /api/tickets/{ticketId}/route",
            "POST /api/tickets/{ticketId}/information-request",
            "POST /api/tickets/{ticketId}/information-response",
            "POST /api/tickets/{ticketId}/resolution",
            "POST /api/tickets/{ticketId}/resolution/confirm",
            "POST /api/tickets/{ticketId}/resolution/reopen"
    );
    private static final Set<String> RESPONSE_SCHEMAS_WITH_STABLE_PRESENCE = Set.of(
            "ApiError",
            "LoginResponse",
            "IdentityResponse",
            "CategoryResponse",
            "NeighborhoodResponse",
            "SubcategoryResponse",
            "RequestTypeResponse",
            "FormDefinitionResponse",
            "FormFieldResponse",
            "CreateTicketResponse",
            "InformationRequestResponse",
            "TicketResolutionResponse",
            "TicketResolutionActionResponse",
            "TrackingTicketResponse",
            "TrackingRequestTypeSummary",
            "TrackingCategorySummary",
            "TrackingSubcategorySummary"
    );

    private static Map<String, Object> spec;
    private static io.swagger.v3.oas.models.OpenAPI parsedOpenApi;

    @BeforeAll
    static void loadSpec() throws Exception {
        InputStream resource = OpenApiContractTest.class.getResourceAsStream("/static/openapi.yaml");
        assertNotNull(resource, "src/main/resources/static/openapi.yaml debe existir");
        spec = new Yaml().load(resource);
        InputStream openApiResource = OpenApiContractTest.class.getResourceAsStream("/static/openapi.yaml");
        assertNotNull(openApiResource);
        parsedOpenApi = io.swagger.v3.core.util.Yaml.mapper()
                .readValue(openApiResource, io.swagger.v3.oas.models.OpenAPI.class);
    }

    @Test
    void versionedFileIsAValidOpenApi3DocumentWithExactlyTheCurrentBusinessOperations() {
        assertTrue(string(spec.get("openapi")).startsWith("3."));
        assertEquals("3.0.3", parsedOpenApi.getOpenapi());
        assertEquals(22, parsedOpenApi.getPaths().size());
        assertNotNull(map(spec, "info").get("title"));
        assertNotNull(map(spec, "components").get("schemas"));
        assertEquals(EXPECTED_OPERATIONS, documentedOperations());
        assertFalse(map(spec, "paths").keySet().stream().anyMatch(path -> path.startsWith("/actuator")));
    }

    @Test
    void documentedOperationsCannotDriftFromMvcControllers() throws Exception {
        assertEquals(controllerOperations(), documentedOperations(),
                "Los mappings MVC de negocio y openapi.yaml deben mantenerse sincronizados");
    }

    @Test
    void bearerSecurityMatchesTheActualPublicBoundary() {
        Map<String, Object> bearer = map(map(spec, "components"), "securitySchemes", "bearerAuth");
        assertEquals("http", bearer.get("type"));
        assertEquals("bearer", bearer.get("scheme"));

        for (String operation : EXPECTED_OPERATIONS) {
            Map<String, Object> operationNode = operation(operation);
            if (PROTECTED_OPERATIONS.contains(operation)) {
                assertEquals("bearerAuth", firstSecurityScheme(operationNode), operation);
            } else if (operation.startsWith("GET /api/catalog/neighborhoods")) {
                assertEquals(java.util.List.of(), operationNode.get("security"), operation);
            } else {
                assertTrue(!operationNode.containsKey("security")
                                || java.util.List.of().equals(operationNode.get("security")),
                        operation + " debe ser público");            }
        }
    }

    @Test
    void ticketCreationDocumentsJsonMultipartAndItsRealContract() {
        Map<String, Object> operation = operation("POST /api/tickets");
        Map<String, Object> content = map(operation, "requestBody", "content");
        assertEquals(Set.of("application/json", "multipart/form-data"), content.keySet());

        Map<String, Object> multipartSchema = map(content, "multipart/form-data", "schema");
        assertEquals(Set.of("data"), Set.copyOf(list(multipartSchema, "required")));
        Map<String, Object> multipartProperties = map(multipartSchema, "properties");
        assertEquals(Set.of("data", "evidence"), multipartProperties.keySet());
        assertEquals("#/components/schemas/CreateTicketRequest", map(multipartProperties, "data").get("$ref"));
        assertEquals("array", map(multipartProperties, "evidence").get("type"));
        assertEquals("binary", map(map(multipartProperties, "evidence"), "items").get("format"));

        Map<String, Object> response422 = map(operation, "responses", "422", "content", "application/json");
        assertEquals("#/components/schemas/ApiError", map(response422, "schema").get("$ref"));
        assertEquals("EVIDENCE_REQUIRED", map(response422, "example").get("code"));
    }

    @Test
    void reusableSchemasExposeOnlyTheCurrentRestModel() {
        Map<String, Object> schemas = map(spec, "components", "schemas");
        assertEquals(Set.of("code", "message"), map(schemas, "ApiError", "properties").keySet());
        assertEquals(Set.of("code", "label", "type", "required", "allowUnknown", "displayOrder", "config"),
                map(schemas, "FormFieldResponse", "properties").keySet());
        assertEquals(Set.of("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9"),
                Set.copyOf(list(map(schemas, "ModuleId"), "enum")));

        String serialized = spec.toString();
        assertFalse(serialized.contains("riskScore"));
        assertFalse(serialized.contains("RiskRule"));
        assertFalse(serialized.contains("riskIncrement"));
        assertFalse(serialized.contains("formTemplateId"));
        assertFalse(serialized.contains("storageKey"));
        assertFalse(serialized.contains("trackingCodeHash"));
        assertFalse(serialized.contains("trackingAccessCode"));

        assertFalse(map(schemas, "TrackingTicketResponse", "properties").containsKey("ticketId"));
        assertEquals(Set.of("name"), map(schemas, "TrackingRequestTypeSummary", "properties").keySet());
    }

    @Test
    void publicIdUsesTheCanonicalReusableContractInEveryExistingResponse() {
        Map<String, Object> schemas = map(spec, "components", "schemas");
        Map<String, Object> publicId = map(schemas, "TicketPublicId");

        assertEquals("^TK-[0-9]{4}-[0-9]{6,}$", publicId.get("pattern"));
        assertEquals("TK-2026-000123", publicId.get("example"));
        for (String responseSchema : Set.of("TicketResponse", "CreateTicketResponse", "TrackingTicketResponse")) {
            assertEquals("#/components/schemas/TicketPublicId",
                    map(schemas, responseSchema, "properties", "publicId").get("$ref"), responseSchema);
        }
    }

    @Test
    void requiredAndNullableMatchTheCurrentJacksonContracts() {
        Map<String, Object> schemas = map(spec, "components", "schemas");
        for (String schemaName : RESPONSE_SCHEMAS_WITH_STABLE_PRESENCE) {
            Map<String, Object> schema = map(schemas, schemaName);
            assertEquals(map(schema, "properties").keySet(), Set.copyOf(list(schema, "required")), schemaName);
        }

        assertTrue(nullableProperty(schemas, "IdentityResponse", "areaId"));
        assertTrue(nullableProperty(schemas, "SubcategoryResponse", "description"));
        assertTrue(nullableProperty(schemas, "InformationRequestResponse", "responseMessage"));
        assertTrue(nullableProperty(schemas, "InformationRequestResponse", "answeredAt"));
        assertFalse(nullableProperty(schemas, "CategoryResponse", "description"));
        assertFalse(nullableProperty(schemas, "RequestTypeResponse", "description"));

        Map<String, Object> createTicket = map(schemas, "CreateTicketRequest");
        assertFalse(list(createTicket, "required").contains("location"));
        assertTrue(nullableProperty(schemas, "CreateTicketRequest", "location"));
        for (String property : map(schemas, "LocationData", "properties").keySet()) {
            assertTrue(nullableProperty(schemas, "LocationData", property), property);
        }
    }

    private static Set<String> documentedOperations() {
        Set<String> operations = new LinkedHashSet<>();
        map(spec, "paths").forEach((path, pathNode) -> map(pathNode).forEach((method, ignored) -> {
            if (HTTP_METHODS.contains(method)) {
                operations.add(method.toUpperCase(Locale.ROOT) + " " + path);
            }
        }));
        return operations;
    }

    private static Set<String> controllerOperations() throws Exception {
        Set<String> operations = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (BeanDefinition candidate : scanner.findCandidateComponents("com.reclamos.backend.controller")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = firstPath(classMapping);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (RequestMethod requestMethod : mapping.method()) {
                    operations.add(requestMethod.name() + normalize(prefix + firstPath(mapping)));
                }
            }
        }
        return operations;
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return "";
        }
        return mapping.path()[0];
    }

    private static String normalize(String path) {
        String normalized = path.replaceAll("/{2,}", "/");
        return " " + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    private static Map<String, Object> operation(String operation) {
        String[] parts = operation.split(" ", 2);
        return map(map(spec, "paths"), parts[1], parts[0].toLowerCase(Locale.ROOT));
    }

    private static String firstSecurityScheme(Map<String, Object> operation) {
        Object security = operation.get("security");
        assertTrue(security instanceof java.util.List<?> && !((java.util.List<?>) security).isEmpty());
        return map(((java.util.List<?>) security).getFirst()).keySet().iterator().next();
    }

    private static boolean nullableProperty(Map<String, Object> schemas, String schemaName, String propertyName) {
        return Boolean.TRUE.equals(map(schemas, schemaName, "properties", propertyName).get("nullable"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, "Se esperaba un objeto YAML y se obtuvo: " + value);
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = map(current).get(key);
            assertNotNull(current, "Falta la clave OpenAPI: " + key);
        }
        return map(current);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> list(Map<String, Object> root, String key) {
        Object value = root.get(key);
        assertTrue(value instanceof java.util.List<?>, "Se esperaba una lista YAML en: " + key);
        return (java.util.List<String>) value;
    }

    private static String string(Object value) {
        assertNotNull(value);
        return value.toString();
    }
}
