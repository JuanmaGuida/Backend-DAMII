package com.reclamos.backend.controller;

import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.InformationRequestConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketControllerErrorTest {
    private final TicketService ticketService = mock(TicketService.class);
    private final InformationRequestService informationRequestService = mock(InformationRequestService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new TicketController(ticketService, informationRequestService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void businessForbiddenUsesCanonicalError() throws Exception {
        when(informationRequestService.requestInformation(any(), any(), nullable(AuthenticatedIdentity.class)))
                .thenThrow(new UnauthorizedTicketOperationException());

        performInformationRequest()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("No está autorizado para realizar esta operación"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void informationRequestConflictUsesCanonicalError() throws Exception {
        when(informationRequestService.requestInformation(any(), any(), nullable(AuthenticatedIdentity.class)))
                .thenThrow(new InformationRequestConflictException("Ya existe una solicitud pendiente"));

        performInformationRequest()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFORMATION_REQUEST_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Ya existe una solicitud pendiente"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void evidenceRequiredUsesCanonicalError() throws Exception {
        when(ticketService.create(any(), nullable(AuthenticatedIdentity.class), any(MultipartFile[].class)))
                .thenThrow(new EvidenceRequiredException());

        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestTypeId": 1,
                                  "summary": "Resumen",
                                  "description": "Descripción",
                                  "formData": {}
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"))
                .andExpect(jsonPath("$.message").value(
                        "El nivel de riesgo detectado requiere adjuntar al menos una evidencia."))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void validationUsesCanonicalBadRequest() throws Exception {
        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/information-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageForCitizen\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unreadableJsonUsesCanonicalBadRequest() throws Exception {
        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/information-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageForCitizen\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("El cuerpo de la solicitud es inválido"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void unsupportedMethodAndMediaTypeUseCanonicalErrors() throws Exception {
        mockMvc.perform(get("/api/tickets/10000000-0000-0000-0000-000000000001/information-request"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/information-request")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("message"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions performInformationRequest() throws Exception {
        return mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/information-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageForCitizen\":\"Dato\"}"));
    }
}
