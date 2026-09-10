package com.reclamos.backend.controller;

import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.InformationRequestConflictException;
import com.reclamos.backend.exception.AttachmentStorageUnavailableException;
import com.reclamos.backend.exception.UnsupportedAttachmentMediaTypeException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketService;
import com.reclamos.backend.service.TicketResolutionService;
import com.reclamos.backend.exception.TicketResolutionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketControllerErrorTest {
    private final TicketService ticketService = mock(TicketService.class);
    private final InformationRequestService informationRequestService = mock(InformationRequestService.class);
    private final TicketResolutionService ticketResolutionService = mock(TicketResolutionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new TicketController(ticketService, informationRequestService, ticketResolutionService))
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
    void resolutionConflictUsesCanonicalError() throws Exception {
        when(ticketResolutionService.resolveManually(any(), any(), nullable(AuthenticatedIdentity.class)))
                .thenThrow(new TicketResolutionConflictException("Estado incompatible"));

        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ACTION_COMPLETED\",\"publicMessage\":\"Listo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_RESOLUTION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Estado incompatible"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void resolutionRequiresTypeAndNonBlankPublicMessage() throws Exception {
        String endpoint = "/api/tickets/10000000-0000-0000-0000-000000000001/resolution";
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicMessage\":\"Listo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ACTION_COMPLETED\",\"publicMessage\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(ticketResolutionService);
    }

    @Test
    void citizenResolutionActionsRouteToService() throws Exception {
        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/resolution/confirm"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/resolution/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"El problema continúa\"}"))
                .andExpect(status().isOk());

        verify(ticketResolutionService).confirm(any(), nullable(AuthenticatedIdentity.class));
        verify(ticketResolutionService).reopen(any(), any(), nullable(AuthenticatedIdentity.class));
    }

    @Test
    void reopenRequiresNonBlankReason() throws Exception {
        String endpoint = "/api/tickets/10000000-0000-0000-0000-000000000001/resolution/reopen";
        mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(ticketResolutionService);
    }

    @Test
    void citizenResolutionActionsUseCanonicalBusinessErrors() throws Exception {
        when(ticketResolutionService.confirm(any(), nullable(AuthenticatedIdentity.class)))
                .thenThrow(new TicketResolutionConflictException("Estado incompatible"));
        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/resolution/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_RESOLUTION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Estado incompatible"))
                .andExpect(jsonPath("$.length()").value(2));

        when(ticketResolutionService.reopen(any(), any(), nullable(AuthenticatedIdentity.class)))
                .thenThrow(new UnauthorizedTicketOperationException());
        mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/resolution/reopen")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Motivo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
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

    @Test
    void multipartAttachmentFailuresUseCanonicalErrors() throws Exception {
        when(ticketService.create(any(), nullable(AuthenticatedIdentity.class), any(MultipartFile[].class)))
                .thenThrow(new UnsupportedAttachmentMediaTypeException("No permitido"));
        mockMvc.perform(multipart("/api/tickets")
                        .file(dataPart())
                        .file(new MockMultipartFile("evidence", "file.zip", "application/zip", new byte[]{1})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        reset(ticketService);
        when(ticketService.create(any(), nullable(AuthenticatedIdentity.class), any(MultipartFile[].class)))
                .thenThrow(new AttachmentStorageUnavailableException());
        mockMvc.perform(multipart("/api/tickets")
                        .file(dataPart())
                        .file(new MockMultipartFile("evidence", "file.jpg", "image/jpeg", new byte[]{1})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_STORAGE_UNAVAILABLE"));

        reset(ticketService);
        when(ticketService.create(any(), nullable(AuthenticatedIdentity.class), any(MultipartFile[].class)))
                .thenThrow(new MaxUploadSizeExceededException(10));
        mockMvc.perform(multipart("/api/tickets")
                        .file(dataPart())
                        .file(new MockMultipartFile("evidence", "file.jpg", "image/jpeg", new byte[]{1})))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    private org.springframework.test.web.servlet.ResultActions performInformationRequest() throws Exception {
        return mockMvc.perform(post("/api/tickets/10000000-0000-0000-0000-000000000001/information-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageForCitizen\":\"Dato\"}"));
    }

    private MockMultipartFile dataPart() {
        return new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, """
                {
                  "requestTypeId": 1,
                  "summary": "Resumen",
                  "description": "Descripción",
                  "formData": {}
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
