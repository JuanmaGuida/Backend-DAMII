package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.TrackingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrackingController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
class TrackingControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrackingService trackingService;
    @MockitoBean
    private IdentityProvider identityProvider;

    @Test
    void validCodeReturnsPublicStatusWithoutAuthenticationOrInternalData() throws Exception {
        when(trackingService.findByTrackingCode("code"))
                .thenReturn(new TrackingTicketResponse("OP-123", TicketStatus.REGISTERED, "Resumen",
                        Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:00:00Z"),
                        new TrackingTicketResponse.RequestTypeSummary(1L, "RT", "Tipo"),
                        new TrackingTicketResponse.CategorySummary(2L, "Categoría"),
                        new TrackingTicketResponse.SubcategorySummary(3L, "Subcategoría")));

        mockMvc.perform(post("/api/tracking/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value("OP-123"))
                .andExpect(jsonPath("$.currentStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.statusChangedAt").value("2026-09-01T10:00:00Z"))
                .andExpect(jsonPath("$.citizenId").doesNotExist())
                .andExpect(jsonPath("$.trackingCodeHash").doesNotExist());
    }

    @Test
    void blankCodeReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/tracking/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("trackingCode: El código de seguimiento es obligatorio"));
    }

    @Test
    void unknownCodeReturnsControlledNotFound() throws Exception {
        when(trackingService.findByTrackingCode("unknown")).thenThrow(new ResourceNotFoundException(
                "No se encontró un ticket para el código de seguimiento informado"));

        mockMvc.perform(post("/api/tracking/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"unknown\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("No se encontró un ticket para el código de seguimiento informado"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ResourceNotFoundException"))));
    }
}