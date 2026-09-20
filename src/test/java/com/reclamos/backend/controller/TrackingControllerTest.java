package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.InvalidAnonymousTicketCredentialsException;
import com.reclamos.backend.exception.TrackingTicketNotFoundException;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrackingController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
class TrackingControllerTest {
    private static final String CODE = "0123456789abcdefghijklmnopqrstuv";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrackingService trackingService;
    @MockitoBean
    private AnonymousTicketAccessService anonymousTicketAccessService;
    @MockitoBean
    private TicketService ticketService;
    @MockitoBean
    private InformationRequestService informationRequestService;
    @MockitoBean
    private TicketResolutionService ticketResolutionService;
    @MockitoBean
    private AuthService authService;

    @Test
    void validCodeReturnsNonCacheablePublicDataWithoutAuthenticationOrReservedFields() throws Exception {
        when(trackingService.findByTrackingCode(CODE)).thenReturn(response());

        mockMvc.perform(post("/api/tracking/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"" + CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.publicId").value("TK-2026-000123"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.summary").value("Resumen"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-01T10:00:00Z"))
                .andExpect(jsonPath("$.statusChangedAt").value("2026-09-01T10:00:00Z"))
                .andExpect(jsonPath("$.requestType.name").value("Tipo"))
                .andExpect(jsonPath("$.category.name").value("Categoría"))
                .andExpect(jsonPath("$.subcategory.name").value("Subcategoría"))
                .andExpect(jsonPath("$.ticketId").doesNotExist())
                .andExpect(jsonPath("$.requestType.code").doesNotExist())
                .andExpect(jsonPath("$.requestType.id").doesNotExist())
                .andExpect(jsonPath("$.category.id").doesNotExist())
                .andExpect(jsonPath("$.subcategory.id").doesNotExist())
                .andExpect(content().string(not(containsString("citizenId"))))
                .andExpect(content().string(not(containsString("trackingCode\""))))
                .andExpect(content().string(not(containsString("trackingCodeHash"))))
                .andExpect(content().string(not(containsString("trackingAccessCode"))))
                .andExpect(content().string(not(containsString("anonymousAccessPassword"))))
                .andExpect(content().string(not(containsString("anonymousContact"))))
                .andExpect(content().string(not(containsString("responsibleAreaId"))))
                .andExpect(content().string(not(containsString("riskScore"))))
                .andExpect(content().string(not(containsString("riskLevel"))))
                .andExpect(content().string(not(containsString("internalMessage"))))
                .andExpect(content().string(not(containsString("actorId"))))
                .andExpect(content().string(not(containsString("resolvedById"))))
                .andExpect(content().string(not(containsString("sourceModuleId"))));
    }

    @Test
    void unknownOrMalformedCodeReturnsTheSameNonCacheableControlledNotFound() throws Exception {
        for (String code : new String[]{CODE, "invalid"}) {
            when(trackingService.findByTrackingCode(code)).thenThrow(new TrackingTicketNotFoundException());

            mockMvc.perform(post("/api/tracking/access")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"trackingCode\":\"" + code + "\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(jsonPath("$.code").value(TrackingTicketNotFoundException.CODE))
                    .andExpect(jsonPath("$.message").value(TrackingTicketNotFoundException.MESSAGE))
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(content().string(not(containsString("TrackingTicketNotFoundException"))));
        }
    }

    @Test
    void accessWithPasswordRequiresAnonymousCredentialsAndKeepsTheSameReadOnlyResponse() throws Exception {
        when(anonymousTicketAccessService.authenticate(CODE, "correct-password"))
                .thenReturn(new AnonymousTicketAccessService.AnonymousTicketAccess(UUID.randomUUID()));
        when(trackingService.findByTrackingCode(CODE)).thenReturn(response());

        mockMvc.perform(post("/api/tracking/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingCode":"%s","anonymousAccessPassword":"correct-password"}
                                """.formatted(CODE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.publicId").value("TK-2026-000123"))
                .andExpect(content().string(not(containsString("anonymousAccessPassword"))));
    }

    @Test
    void invalidAnonymousCredentialsAreUniformAndDoNotInvokeAnAction() throws Exception {
        when(anonymousTicketAccessService.authenticate(CODE, "wrong-password"))
                .thenThrow(new InvalidAnonymousTicketCredentialsException());

        mockMvc.perform(post("/api/tracking/actions/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "trackingCode":"%s",
                                  "anonymousAccessPassword":"wrong-password",
                                  "payload":{"reasonCode":"WITHDRAWN_BY_CITIZEN"}
                                }
                                """.formatted(CODE)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(InvalidAnonymousTicketCredentialsException.CODE))
                .andExpect(content().string(not(containsString(CODE))))
                .andExpect(content().string(not(containsString("wrong-password"))));

        mockMvc.perform(post("/api/tracking/actions/information-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingCode":"%s","anonymousAccessPassword":"wrong-password",
                                 "payload":{"responseMessage":"Respuesta"}}
                                """.formatted(CODE)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tracking/actions/confirm-resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingCode":"%s","anonymousAccessPassword":"wrong-password"}
                                """.formatted(CODE)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tracking/actions/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingCode":"%s","anonymousAccessPassword":"wrong-password",
                                 "payload":{"reason":"Continúa"}}
                                """.formatted(CODE)))
                .andExpect(status().isUnauthorized());

        verify(ticketService, never()).cancelAnonymousTicket(any(), any());
        verify(informationRequestService, never()).answerAnonymousFromTracking(any(), any());
        verify(ticketResolutionService, never()).confirmAnonymous(any());
        verify(ticketResolutionService, never()).reopenAnonymous(any(), any());
    }

    @Test
    void anonymousActionFacadesArePublicAndDelegateOnlyAfterAuthentication() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(anonymousTicketAccessService.authenticate(CODE, "correct-password"))
                .thenReturn(new AnonymousTicketAccessService.AnonymousTicketAccess(ticketId));

        mockMvc.perform(post("/api/tracking/actions/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson("{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(post("/api/tracking/actions/information-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson("{\"responseMessage\":\"Respuesta\"}")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(post("/api/tracking/actions/confirm-resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingCode":"%s","anonymousAccessPassword":"correct-password"}
                                """.formatted(CODE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(post("/api/tracking/actions/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson("{\"reason\":\"El problema continúa\"}")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));

        verify(ticketService).cancelAnonymousTicket(eq(ticketId), any());
        verify(informationRequestService).answerAnonymousFromTracking(eq(ticketId), any());
        verify(ticketResolutionService).confirmAnonymous(ticketId);
        verify(ticketResolutionService).reopenAnonymous(eq(ticketId), any());
        verify(anonymousTicketAccessService, times(4)).authenticate(CODE, "correct-password");
    }

    void formerPublicGetRouteIsNoLongerExposed() throws Exception {
        mockMvc.perform(get("/api/public/tickets/track/{trackingCode}", CODE))
                .andExpect(status().isUnauthorized());
    }

    private TrackingTicketResponse response() {
        return new TrackingTicketResponse(
                "TK-2026-000123", TicketStatus.REGISTERED, "Resumen", Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-01T10:00:00Z"),
                new TrackingTicketResponse.RequestTypeSummary("Tipo"),
                new TrackingTicketResponse.CategorySummary("Categoría"),
                new TrackingTicketResponse.SubcategorySummary("Subcategoría"),
                new TrackingTicketResponse.SlaSummary(null, null));    }

    private String actionJson(String payload) {
        return """
                {"trackingCode":"%s","anonymousAccessPassword":"correct-password","payload":%s}
                """.formatted(CODE, payload);
    }
}
