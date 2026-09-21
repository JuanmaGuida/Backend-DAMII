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
            "GET /api/admin/catalog/categories",
            "POST /api/admin/catalog/categories",
            "PUT /api/admin/catalog/categories/{categoryId}",
            "GET /api/admin/catalog/categories/{categoryId}/subcategories",
            "POST /api/admin/catalog/categories/{categoryId}/deactivate",
            "POST /api/admin/catalog/categories/{categoryId}/activate",
            "POST /api/admin/catalog/subcategories",
            "PUT /api/admin/catalog/subcategories/{subcategoryId}",
            "POST /api/admin/catalog/subcategories/{subcategoryId}/deactivate",
            "POST /api/admin/catalog/subcategories/{subcategoryId}/activate",
            "GET /api/admin/catalog/subcategories/{subcategoryId}/request-types",
            "POST /api/admin/catalog/request-types",
            "PUT /api/admin/catalog/request-types/{requestTypeId}",
            "POST /api/admin/catalog/request-types/{requestTypeId}/deactivate",
            "POST /api/admin/catalog/request-types/{requestTypeId}/activate",
            "GET /api/admin/catalog/request-types/{requestTypeId}/form",
            "PUT /api/admin/catalog/request-types/{requestTypeId}/form",
            "GET /api/indicators/categories",
            "GET /api/indicators/neighborhoods",
            "GET /api/indicators/priorities",
            "GET /api/indicators/areas",
            "GET /api/indicators/sla",
            "GET /api/me/tickets",
            "GET /api/tickets",
            "POST /api/tickets",
            "GET /api/tickets/{ticketId}",
            "POST /api/tickets/{ticketId}/attachments",
            "GET /api/staff/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}/citizen-view",
            "GET /api/staff/tickets/{ticketId}/duplicate-candidates",
            "POST /api/staff/tickets/{ticketId}/duplicate",
            "GET /api/staff/labels",
            "POST /api/staff/labels",
            "GET /api/staff/labels/{labelId}",
            "PUT /api/staff/labels/{labelId}",
            "DELETE /api/staff/labels/{labelId}",
            "GET /api/staff/labels/{labelId}/tickets",
            "POST /api/staff/tickets/{ticketId}/labels",
            "DELETE /api/staff/tickets/{ticketId}/labels/{labelId}",
            "POST /api/tickets/{ticketId}/review",
            "PATCH /api/tickets/{ticketId}/classification",
            "POST /api/tickets/{ticketId}/route",
            "POST /api/tickets/{ticketId}/information-request",
            "POST /api/tickets/{ticketId}/information-response",
            "POST /api/tickets/{ticketId}/resolution",
            "POST /api/tickets/{ticketId}/resolution/confirm",
            "POST /api/tickets/{ticketId}/resolution/reopen",
            "POST /api/tickets/{ticketId}/satisfaction-survey",
            "POST /api/tickets/{ticketId}/cancel",
            "POST /api/tickets/{ticketId}/messages",
            "GET /api/tickets/{ticketId}/messages",
            "PATCH /api/tickets/{ticketId}/messages/{messageId}",
            "DELETE /api/tickets/{ticketId}/messages/{messageId}",
            "GET /api/attachments/{attachmentId}/content",
            "POST /api/tracking/access",
            "POST /api/tracking/actions/cancel",
            "POST /api/tracking/actions/information-response",
            "POST /api/tracking/actions/confirm-resolution",
            "POST /api/tracking/actions/reopen",
            "POST /api/tracking/actions/attachments",
            "POST /api/tracking/actions/attachments/{attachmentId}/content"
    );
    private static final Set<String> PROTECTED_OPERATIONS = Set.of(
            "GET /api/auth/me",
            "GET /api/admin/catalog/categories",
            "POST /api/admin/catalog/categories",
            "PUT /api/admin/catalog/categories/{categoryId}",
            "GET /api/admin/catalog/categories/{categoryId}/subcategories",
            "POST /api/admin/catalog/categories/{categoryId}/deactivate",
            "POST /api/admin/catalog/categories/{categoryId}/activate",
            "POST /api/admin/catalog/subcategories",
            "PUT /api/admin/catalog/subcategories/{subcategoryId}",
            "POST /api/admin/catalog/subcategories/{subcategoryId}/deactivate",
            "POST /api/admin/catalog/subcategories/{subcategoryId}/activate",
            "GET /api/admin/catalog/subcategories/{subcategoryId}/request-types",
            "POST /api/admin/catalog/request-types",
            "PUT /api/admin/catalog/request-types/{requestTypeId}",
            "POST /api/admin/catalog/request-types/{requestTypeId}/deactivate",
            "POST /api/admin/catalog/request-types/{requestTypeId}/activate",
            "GET /api/admin/catalog/request-types/{requestTypeId}/form",
            "PUT /api/admin/catalog/request-types/{requestTypeId}/form",
            "GET /api/indicators/categories",
            "GET /api/indicators/neighborhoods",
            "GET /api/indicators/priorities",
            "GET /api/indicators/areas",
            "GET /api/indicators/sla",
            "GET /api/me/tickets",
            "GET /api/tickets",
            "GET /api/tickets/{ticketId}",
            "POST /api/tickets/{ticketId}/attachments",
            "GET /api/staff/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}/citizen-view",
            "GET /api/staff/tickets/{ticketId}/duplicate-candidates",
            "POST /api/staff/tickets/{ticketId}/duplicate",
            "GET /api/staff/labels",
            "POST /api/staff/labels",
            "GET /api/staff/labels/{labelId}",
            "PUT /api/staff/labels/{labelId}",
            "DELETE /api/staff/labels/{labelId}",
            "GET /api/staff/labels/{labelId}/tickets",
            "POST /api/staff/tickets/{ticketId}/labels",
            "DELETE /api/staff/tickets/{ticketId}/labels/{labelId}",
            "POST /api/tickets/{ticketId}/review",
            "PATCH /api/tickets/{ticketId}/classification",
            "POST /api/tickets/{ticketId}/route",
            "POST /api/tickets/{ticketId}/information-request",
            "POST /api/tickets/{ticketId}/information-response",
            "POST /api/tickets/{ticketId}/resolution",
            "POST /api/tickets/{ticketId}/resolution/confirm",
            "POST /api/tickets/{ticketId}/resolution/reopen",
            "POST /api/tickets/{ticketId}/cancel",
            "POST /api/tickets/{ticketId}/satisfaction-survey",
            "POST /api/tickets/{ticketId}/messages",
            "GET /api/tickets/{ticketId}/messages",
            "PATCH /api/tickets/{ticketId}/messages/{messageId}",
            "DELETE /api/tickets/{ticketId}/messages/{messageId}",
            "GET /api/attachments/{attachmentId}/content"
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
            "SatisfactionSurveyResponse",
            "TicketDetailResponse",
            "TicketAttachmentResponse",
            "TicketActivityResponse",
            "TicketMessageResponse",
            "TrackingTicketResponse",
            "TrackingRequestTypeSummary",
            "TrackingCategorySummary",
            "TrackingSubcategorySummary",
            "CategoryAdminResponse",
            "SubcategoryAdminResponse",
            "RequestTypeAdminResponse",
            "FormTemplateAdminResponse",
            "FormFieldAdminResponse",
            "RiskRuleAdminResponse",
            "LabelResponse",
            "LabelSummaryResponse",
            "CategoryIndicatorResponse",
            "NeighborhoodIndicatorResponse",
            "PriorityIndicatorResponse",
            "AreaIndicatorResponse",
            "SlaIndicatorResponse"
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
        assertEquals(61, parsedOpenApi.getPaths().size());
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
            if (operation.equals("POST /api/tickets")) {
                assertEquals(java.util.List.of(Map.of(), Map.of("bearerAuth", java.util.List.of())),
                        operationNode.get("security"), operation);
            } else if (PROTECTED_OPERATIONS.contains(operation)) {
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
    void indicatorOperationsShareFiltersResponsesAndDocumentLatestSlaCycleSemantics() {
        Set<String> expectedParameters = Set.of("categoryId", "neighborhoodId", "priority",
                "responsibleAreaId", "status", "slaType", "slaStatus");
        for (String path : Set.of("categories", "neighborhoods", "priorities", "areas", "sla")) {
            Map<String, Object> operation = operation("GET /api/indicators/" + path);
            Set<String> names = new LinkedHashSet<>();
            for (Object rawParameter : (java.util.List<?>) operation.get("parameters")) {
                String reference = string(map(rawParameter).get("$ref"));
                String componentName = reference.substring(reference.lastIndexOf('/') + 1);
                names.add(string(map(map(spec, "components"), "parameters", componentName).get("name")));
            }
            assertEquals(expectedParameters, names, path);
            assertEquals(Set.of("200", "400", "401", "403"), map(operation, "responses").keySet(), path);
        }
        String description = string(operation("GET /api/indicators/sla").get("description"));
        assertTrue(description.contains("MAX cycleNumber"));
        assertTrue(description.contains("independientes"));
        assertTrue(string(map(map(spec, "components"), "parameters", "IndicatorSlaStatus")
                .get("description")).contains("al menos un tipo"));
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

        Map<String, Object> schemas = map(spec, "components", "schemas");
        Map<String, Object> requestProperties = map(schemas, "CreateTicketRequest", "properties");
        assertEquals(Set.of("requestTypeId", "summary", "description", "formData", "location",
                        "anonymousAccessPassword", "anonymousContact"),
                requestProperties.keySet());
        assertEquals("#/components/schemas/AnonymousContact",
                map(list(map(requestProperties, "anonymousContact"), "allOf").getFirst()).get("$ref"));
        assertEquals(Set.of("channel", "value"), map(schemas, "AnonymousContact", "properties").keySet());
        assertEquals(Set.of("EMAIL", "PHONE"),
                Set.copyOf(list(map(schemas, "AnonymousContactChannel"), "enum")));
        assertEquals(254, map(schemas, "AnonymousContact", "properties", "value").get("maxLength"));

        Map<String, Object> responseProperties = map(schemas, "CreateTicketResponse", "properties");
        assertTrue(responseProperties.containsKey("generatedAnonymousAccessPassword"));
        assertTrue(nullableProperty(schemas, "CreateTicketResponse", "generatedAnonymousAccessPassword"));
        assertEquals("no-store", map(operation, "responses", "201", "headers", "Cache-Control", "schema").get("example"));
    }

    @Test
    void anonymousOwnerActionsDocumentBodyCredentialsWithoutSessionsOrHashes() {
        Map<String, Object> schemas = map(spec, "components", "schemas");
        assertEquals(Set.of("trackingCode", "anonymousAccessPassword"),
                map(schemas, "AnonymousTicketCredentialsRequest", "properties").keySet());
        assertTrue(map(schemas, "TrackingAccessRequest", "properties")
                .containsKey("anonymousAccessPassword"));
        assertEquals("no-store", map(operation("POST /api/tracking/access"),
                "responses", "200", "headers", "Cache-Control", "schema").get("example"));

        for (String operationName : Set.of(
                "POST /api/tracking/actions/cancel",
                "POST /api/tracking/actions/information-response",
                "POST /api/tracking/actions/confirm-resolution",
                "POST /api/tracking/actions/reopen",
                "POST /api/tracking/actions/attachments/{attachmentId}/content")) {
            Map<String, Object> operation = operation(operationName);
            assertEquals(java.util.List.of(), operation.get("security"), operationName);
            assertEquals("#/components/responses/InvalidAnonymousTicketCredentials",
                    map(operation, "responses", "401").get("$ref"), operationName);
            assertEquals("no-store",
                    map(operation, "responses", "200", "headers", "Cache-Control", "schema").get("example"),
                    operationName);
        }

        String serialized = spec.toString();
        assertFalse(serialized.contains("anonymousAccessPasswordHash"));
        assertFalse(serialized.contains("trackingCodeHash"));
        assertFalse(serialized.contains("sessionToken"));
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
        assertFalse(serialized.contains("formTemplateId"));
        assertFalse(serialized.contains("storageKey"));
        assertFalse(serialized.contains("trackingCodeHash"));
        assertFalse(serialized.contains("trackingAccessCode"));
        assertFalse(serialized.contains("anonymousAccessPasswordHash"));
        assertFalse(serialized.contains("anonymousContactValue"));

        assertFalse(map(schemas, "TrackingTicketResponse", "properties").containsKey("ticketId"));
        assertTrue(map(schemas, "TrackingTicketResponse", "properties").containsKey("description"));
        assertTrue(map(schemas, "TrackingTicketResponse", "properties").containsKey("attachments"));
        assertEquals("#/components/schemas/TicketAttachmentResponse",
                map(schemas, "TrackingTicketResponse", "properties", "attachments", "items").get("$ref"));
        assertEquals(Set.of("name"), map(schemas, "TrackingRequestTypeSummary", "properties").keySet());
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("escalationReasonCode"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("escalatedAt"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("firstResponseDueAt"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("firstResponseNearDue"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("firstResponseBreached"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("resolutionDueAt"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("slaNearDue"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("slaBreached"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("resolutionNearDueAt"));
        assertTrue(map(schemas, "TicketResponse", "properties").containsKey("neighborhoodId"));
        assertFalse(map(schemas, "TicketResponse", "properties").containsKey("attachments"));
        assertFalse(map(schemas, "TicketResponse", "properties").containsKey("ticketActivities"));

        Map<String, Object> detailProperties = map(schemas, "TicketDetailResponse", "properties");
        assertTrue(detailProperties.containsKey("description"));
        assertTrue(detailProperties.containsKey("attachments"));
        assertTrue(detailProperties.containsKey("ticketActivities"));
        assertTrue(detailProperties.containsKey("pendingInformationRequest"));
        assertTrue(detailProperties.containsKey("neighborhoodName"));
        assertFalse(detailProperties.containsKey("neighborhoodId"));
        assertFalse(detailProperties.containsKey("anonymousContact"));
        assertFalse(map(schemas, "TicketResponse", "properties").containsKey("anonymousContact"));
        assertFalse(map(schemas, "TrackingTicketResponse", "properties").containsKey("anonymousContact"));

        java.util.List<String> staffDetailAllOf = list(map(schemas, "StaffTicketDetailResponse"), "allOf");
        assertEquals("#/components/schemas/TicketDetailResponse", map(staffDetailAllOf.getFirst()).get("$ref"));
        Map<String, Object> staffProperties = map(map(staffDetailAllOf.get(1)), "properties");
        assertEquals(Set.of("anonymousContact", "pendingInformationRequestContext"), staffProperties.keySet());
        assertEquals("#/components/schemas/AnonymousContact",
                map(list(map(staffProperties, "anonymousContact"), "allOf").getFirst()).get("$ref"));
        assertTrue(map(schemas, "TrackingTicketResponse", "properties")
                .containsKey("pendingInformationRequest"));

        Map<String, Object> attachmentProperties = map(schemas, "TicketAttachmentResponse", "properties");
        assertEquals(Set.of("id", "fileName", "contentType", "sizeBytes", "visibility", "createdAt", "downloadUrl"),
                attachmentProperties.keySet());
        assertEquals("uri", map(attachmentProperties, "downloadUrl").get("format"));

        Map<String, Object> activityProperties = map(schemas, "TicketActivityResponse", "properties");
        assertTrue(map(schemas, "TicketActivityResponse").get("description").toString()
                .contains("message también es nulo salvo para REOPENED"));
        assertTrue(list(map(schemas, "TicketActivityResponse"), "required").contains("occurredAt"));
        assertFalse(nullableProperty(schemas, "TicketActivityResponse", "occurredAt"));
        assertFalse(activityProperties.containsKey("actorId"));
        assertFalse(activityProperties.containsKey("sourceModuleId"));
        assertFalse(activityProperties.containsKey("externalEventId"));
        assertFalse(activityProperties.containsKey("metadata"));
        assertFalse(activityProperties.containsKey("ticketVersion"));
        assertEquals(Set.of("CRITICAL_PRIORITY", "SLA_BREACHED", "MANUAL"),
                Set.copyOf(list(map(schemas, "EscalationReasonCode"), "enum")));
        assertEquals(Set.of("PENDING", "ANSWERED", "EXPIRED", "CANCELLED"),
                Set.copyOf(list(map(schemas, "InformationRequestStatus"), "enum")));
        Map<String, Object> informationResponse = map(schemas, "InformationRequestResponse", "properties");
        assertTrue(informationResponse.containsKey("currentStatus"));
        assertFalse(informationResponse.containsKey("informationRequestId"));
        assertFalse(informationResponse.containsKey("resumeStatus"));
        assertFalse(informationResponse.containsKey("internalMessage"));
        assertFalse(informationResponse.containsKey("requestedByActorId"));
    }

    @Test
    void generalAttachmentUploadsDocumentMultipartVisibilityAndSafeResponses() {
        Map<String, Object> identified = operation("POST /api/tickets/{ticketId}/attachments");
        Map<String, Object> identifiedSchema = map(identified, "requestBody", "content",
                "multipart/form-data", "schema");
        assertEquals(Set.of("data", "attachments"), Set.copyOf(list(identifiedSchema, "required")));
        assertEquals("#/components/schemas/AttachmentUploadRequest",
                map(identifiedSchema, "properties", "data").get("$ref"));
        assertEquals("binary", map(identifiedSchema, "properties", "attachments", "items").get("format"));
        assertEquals("#/components/schemas/TicketAttachmentResponse",
                map(identified, "responses", "201", "content", "application/json", "schema", "items")
                        .get("$ref"));

        Map<String, Object> anonymous = operation("POST /api/tracking/actions/attachments");
        assertEquals(java.util.List.of(), anonymous.get("security"));
        assertEquals("#/components/schemas/AnonymousAttachmentUploadRequest",
                map(anonymous, "requestBody", "content", "multipart/form-data", "schema", "properties", "data")
                        .get("$ref"));
        assertEquals("#/components/responses/InvalidAnonymousTicketCredentials",
                map(anonymous, "responses", "401").get("$ref"));
        assertEquals("no-store",
                map(anonymous, "responses", "201", "headers", "Cache-Control", "schema").get("example"));

        Map<String, Object> visibility = map(map(spec, "components", "schemas"),
                "AttachmentUploadRequest", "properties", "visibility");
        assertEquals(Set.of("PUBLIC", "INTERNAL"), Set.copyOf(list(visibility, "enum")));
    }

    @Test
    void publicIdUsesTheCanonicalReusableContractInEveryExistingResponse() {
        Map<String, Object> schemas = map(spec, "components", "schemas");
        Map<String, Object> publicId = map(schemas, "TicketPublicId");

        assertEquals("^TK-[0-9]{4}-[0-9]{6,}$", publicId.get("pattern"));
        assertEquals("TK-2026-000123", publicId.get("example"));
        for (String responseSchema : Set.of(
                "TicketResponse", "TicketDetailResponse", "CreateTicketResponse", "TrackingTicketResponse")) {
            assertEquals("#/components/schemas/TicketPublicId",
                    map(schemas, responseSchema, "properties", "publicId").get("$ref"), responseSchema);
        }
    }

    @Test
    void requestTypeAdminDocumentsConditionalBaseRiskMutabilityAndItsFunctionalError() {
        Map<String, Object> schemas = map(spec, "components", "schemas");
        Map<String, Object> baseRisk = map(schemas, "RequestTypeAdminRequest", "properties", "baseRisk");
        String description = string(baseRisk.get("description"));

        assertTrue(description.contains("mientras el Request Type no tenga tickets asociados"));
        assertTrue(description.contains("sólo se acepta conservar el mismo valor"));
        assertTrue(baseRisk.get("allOf") instanceof java.util.List<?>);
        assertEquals("#/components/schemas/Risk",
                map(((java.util.List<?>) baseRisk.get("allOf")).getFirst()).get("$ref"));
        assertEquals("#/components/responses/InvalidRequest",
                map(operation("PUT /api/admin/catalog/request-types/{requestTypeId}"), "responses", "400")
                        .get("$ref"));
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
        assertEquals("#/components/responses/Forbidden",
                map(operation("GET /api/tickets"), "responses", "403").get("$ref"));
        return operations;
    }

    @Test
    void detailOperationsUseTheDedicatedResponseWithoutChangingSharedTicketResponses() {
        for (String operationName : Set.of(
                "GET /api/tickets/{ticketId}",
                "GET /api/staff/tickets/{ticketId}/citizen-view")) {
            assertEquals("#/components/schemas/TicketDetailResponse",
                    map(operation(operationName), "responses", "200", "content", "application/json", "schema")
                            .get("$ref"), operationName);
        }
        assertEquals("#/components/schemas/StaffTicketDetailResponse",
                map(operation("GET /api/staff/tickets/{ticketId}"),
                        "responses", "200", "content", "application/json", "schema").get("$ref"));
        for (String operationName : Set.of(
                "POST /api/tickets/{ticketId}/review",
                "PATCH /api/tickets/{ticketId}/classification",
                "POST /api/tickets/{ticketId}/route",
                "POST /api/tickets/{ticketId}/cancel")) {
            assertEquals("#/components/schemas/TicketResponse",
                    map(operation(operationName), "responses", "200", "content", "application/json", "schema")
                            .get("$ref"), operationName);
        }
        assertEquals("#/components/schemas/PagedTicketResponse",
                map(operation("GET /api/tickets"), "responses", "200", "content", "application/json", "schema")
                        .get("$ref"));
        assertEquals("#/components/schemas/PagedTicketResponse",
                map(operation("GET /api/me/tickets"), "responses", "200", "content", "application/json", "schema")
                        .get("$ref"));
    }

    @Test
    void staffTicketListDocumentsOptionalAnyLabelIdsFilterOnlyOnTheSupportedEndpoint() {
        Map<String, Object> staffList = operation("GET /api/tickets");
        java.util.List<?> parameters = (java.util.List<?>) staffList.get("parameters");
        Map<String, Object> labelIds = parameters.stream()
                .map(value -> map(value))
                .filter(parameter -> "labelIds".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals("query", labelIds.get("in"));
        assertEquals(Boolean.FALSE, labelIds.get("required"));
        assertTrue(labelIds.get("description").toString().contains("ANY"));
        assertEquals("array", map(labelIds, "schema").get("type"));
        assertEquals("uuid", map(labelIds, "schema", "items").get("format"));

        java.util.List<?> meParameters = (java.util.List<?>) operation("GET /api/me/tickets").get("parameters");
        assertFalse(meParameters.stream().map(value -> map(value))
                .anyMatch(parameter -> "labelIds".equals(parameter.get("name"))));
    }

    @Test
    void labelTicketsOperationIsPagedProtectedAndDocumentsItsResponses() {
        Map<String, Object> operation = operation("GET /api/staff/labels/{labelId}/tickets");
        assertEquals("bearerAuth", firstSecurityScheme(operation));
        assertEquals("#/components/schemas/PagedTicketResponse",
                map(operation, "responses", "200", "content", "application/json", "schema").get("$ref"));
        assertEquals("#/components/responses/InvalidToken", map(operation, "responses", "401").get("$ref"));
        assertEquals("#/components/responses/Forbidden", map(operation, "responses", "403").get("$ref"));
        assertEquals("#/components/responses/NotFound", map(operation, "responses", "404").get("$ref"));

        java.util.List<?> parameters = (java.util.List<?>) operation.get("parameters");
        assertEquals(Set.of("labelId", "page", "size", "sort"), parameters.stream()
                .map(value -> map(value).get("name").toString())
                .collect(java.util.stream.Collectors.toSet()));
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
