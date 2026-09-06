package com.reclamos.backend.security;

import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.dto.response.FormDefinitionResponse;
import com.reclamos.backend.dto.response.InformationRequestResponse;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.service.CatalogService;
import com.reclamos.backend.service.FormService;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketService;
import com.reclamos.backend.service.TrackingService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SecurityHardeningIntegrationTest {
    private static final UUID TICKET_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID INFORMATION_REQUEST_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdentityProvider identityProvider;

    @MockitoBean
    private CatalogService catalogService;
    @MockitoBean
    private FormService formService;
    @MockitoBean
    private TrackingService trackingService;
    @MockitoBean
    private TicketService ticketService;
    @MockitoBean
    private InformationRequestService informationRequestService;

    @BeforeEach
    void configureControllerResponses() {
        when(catalogService.getCategories()).thenReturn(List.of());
        when(catalogService.getSubcategories(1L)).thenReturn(List.of());
        when(catalogService.getRequestTypes(1L)).thenReturn(List.of());

        FormDefinitionResponse form = new FormDefinitionResponse();
        form.setRequestTypeId(1L);
        form.setRequestTypeCode("TEST");
        form.setVersion(1);
        form.setFields(List.of());
        when(formService.getFormForRequestType(1L)).thenReturn(form);

        when(trackingService.findByTrackingCode("tracking-code")).thenReturn(new TrackingTicketResponse(
                "OP-123", TicketStatus.REGISTERED, "Resumen",
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:00:00Z"),
                new TrackingTicketResponse.RequestTypeSummary(1L, "TEST", "Tipo"),
                new TrackingTicketResponse.CategorySummary(1L, "Categoría"),
                new TrackingTicketResponse.SubcategorySummary(1L, "Subcategoría")));

        when(ticketService.create(any(), any(), any())).thenReturn(new CreateTicketResponse(
                TICKET_ID, "OP-123", "tracking-code", TicketStatus.REGISTERED));
        InformationRequestResponse informationResponse = new InformationRequestResponse(
                INFORMATION_REQUEST_ID, TICKET_ID, InformationRequestStatus.PENDING, "Dato requerido",
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-04T10:00:00Z"),
                TicketStatus.IN_PROGRESS, null, null);
        when(informationRequestService.requestInformation(any(), any(), any())).thenReturn(informationResponse);
        when(informationRequestService.answerInformation(any(), any(), any())).thenReturn(informationResponse);
    }

    @Test
    void devLoginAndEveryDeclaredPublicEndpointAreReachableWithoutToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"citizen@example.test","password":"CitizenDev!2026"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/catalog/categories")).andExpect(status().isOk());
        mockMvc.perform(get("/api/catalog/categories/1/subcategories")).andExpect(status().isOk());
        mockMvc.perform(get("/api/catalog/subcategories/1/request-types")).andExpect(status().isOk());
        mockMvc.perform(get("/api/catalog/request-types/1/form")).andExpect(status().isOk());

        mockMvc.perform(post("/api/tracking/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"tracking-code\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidBearerDoesNotProtectPublicCatalogOrTracking() throws Exception {
        mockMvc.perform(get("/api/catalog/categories")
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tracking/access")
                        .header("Authorization", "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"tracking-code\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void headForEveryPublicCatalogGetIsAlsoPublic() throws Exception {
        mockMvc.perform(head("/api/catalog/categories")).andExpect(status().isOk());
        mockMvc.perform(head("/api/catalog/categories/1/subcategories")).andExpect(status().isOk());
        mockMvc.perform(head("/api/catalog/subcategories/1/request-types")).andExpect(status().isOk());
        mockMvc.perform(head("/api/catalog/request-types/1/form")).andExpect(status().isOk());
    }

    @Test
    void healthBaseIsPublicButAllOtherActuatorPathsAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());

        assertCanonicalUnauthorized(get("/actuator"));
        assertCanonicalUnauthorized(get("/actuator/health/db"));

        String token = validToken();
        assertCanonicalForbidden(withBearer(get("/actuator"), token));
        assertCanonicalForbidden(withBearer(get("/actuator/health/db"), token));
    }

    @Test
    void swaggerUiCanonicalSpecAndGeneratedViewArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mockMvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("openapi: 3.")));
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi.yaml"));
    }

    @Test
    void everyProtectedEndpointReturnsCanonicalUnauthorizedWithoutValidToken() throws Exception {
        List<RequestBuilder> requests = List.of(
                get("/api/auth/me"),
                ticketJsonRequest(),
                informationRequest(),
                informationResponse()
        );
        for (RequestBuilder request : requests) {
            assertCanonicalUnauthorized(request);
        }

        assertCanonicalUnauthorized(multipartTicketRequest());

        List<MockHttpServletRequestBuilder> invalidBearerRequests = List.of(
                get("/api/auth/me"),
                ticketJsonRequest(),
                informationRequest(),
                informationResponse()
        );
        for (MockHttpServletRequestBuilder request : invalidBearerRequests) {
            assertCanonicalUnauthorized(request.header("Authorization", "Bearer invalid"));
        }
    }

    @Test
    void validBearerReachesEveryProtectedControllerVariant() throws Exception {
        String token = validToken();
        mockMvc.perform(withBearer(get("/api/auth/me"), token)).andExpect(status().isOk());
        mockMvc.perform(withBearer(ticketJsonRequest(), token)).andExpect(status().isCreated());
        mockMvc.perform(withBearer(multipartTicketRequest(), token)).andExpect(status().isCreated());
        mockMvc.perform(withBearer(informationRequest(), token)).andExpect(status().isCreated());
        mockMvc.perform(withBearer(informationResponse(), token)).andExpect(status().isOk());
    }

    @Test
    void authenticatedMultipartWithoutRequiredDataUsesCanonicalBadRequest() throws Exception {
        mockMvc.perform(withBearer(multipart("/api/tickets"), validToken()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Falta el part obligatorio 'data'"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    void unknownAndWrongMethodRequestsUseTheApprovedAuthenticationBoundary() throws Exception {
        assertCanonicalUnauthorized(get("/api/not-a-real-endpoint"));
        assertCanonicalUnauthorized(get("/api/tickets/" + TICKET_ID + "/information-request"));

        String token = validToken();
        mockMvc.perform(withBearer(get("/api/not-a-real-endpoint"), token))
                .andExpect(status().isNotFound());
        mockMvc.perform(withBearer(get("/api/tickets/" + TICKET_ID + "/information-request"), token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void authenticatedMvcErrorsKeepTheirCanonicalResponseInsteadOfSecondarySecurityErrors() throws Exception {
        mockMvc.perform(withBearer(post("/api/tickets/not-a-uuid/information-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageForCitizen\":\"Dato\"}"), validToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        int errorDispatchStatus = mockMvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, errorDispatchStatus);
        assertNotEquals(403, errorDispatchStatus);
    }

    @Test
    void crossOriginOptionsIsNotOpenedAsAReplacementForCorsConfiguration() throws Exception {
        assertCanonicalUnauthorized(options("/api/tickets")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST"));
    }

    private String validToken() {
        return identityProvider.authenticate("citizen@example.test", "CitizenDev!2026")
                .orElseThrow()
                .token();
    }

    private MockHttpServletRequestBuilder ticketJsonRequest() {
        return post("/api/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "requestTypeId": 1,
                          "summary": "Resumen",
                          "description": "Descripción",
                          "formData": {}
                        }
                        """);
    }

    private MockMultipartHttpServletRequestBuilder multipartTicketRequest() {
        MockMultipartFile data = new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, """
                {
                  "requestTypeId": 1,
                  "summary": "Resumen",
                  "description": "Descripción",
                  "formData": {}
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return multipart("/api/tickets").file(data);
    }

    private MockHttpServletRequestBuilder informationRequest() {
        return post("/api/tickets/" + TICKET_ID + "/information-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageForCitizen\":\"Dato\"}");
    }

    private MockHttpServletRequestBuilder informationResponse() {
        return post("/api/tickets/" + TICKET_ID + "/information-response")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"responseMessage\":\"Respuesta\"}");
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B withBearer(B request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private void assertCanonicalUnauthorized(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.message").value("La sesión no es válida"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(header().doesNotExist("Location"));
    }

    private void assertCanonicalForbidden(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("No tiene permisos para realizar esta operación"))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
