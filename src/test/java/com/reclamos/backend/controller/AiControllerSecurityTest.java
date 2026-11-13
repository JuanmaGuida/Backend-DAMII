package com.reclamos.backend.controller;

import com.reclamos.backend.ai.AiClassificationService;
import com.reclamos.backend.ai.AiClassifyResponse;
import com.reclamos.backend.ai.AiTicketInsightsResponse;
import com.reclamos.backend.ai.AiTicketInsightsService;
import com.reclamos.backend.ai.TooManyAiRequestsException;
import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
class AiControllerSecurityTest {
    static final String CLASSIFY = "/api/ai/classify";
    static final String INSIGHTS = "/api/staff/tickets/{ticketId}/ai-insights";

    @Autowired MockMvc mvc;
    @MockitoBean AiClassificationService classification;
    @MockitoBean AiTicketInsightsService insights;
    @MockitoBean AuthService authService;

    static RequestPostProcessor as(ModuleRole role) {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(role.name(), UUID.randomUUID(), role.name(),
                role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null, role);
        return authentication(new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @Test
    void classifyIsPublicBecauseTheClaimMayBeAnonymous() throws Exception {
        when(classification.classify(any())).thenReturn(AiClassifyResponse.unavailable());

        mvc.perform(post(CLASSIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"La luz de la esquina no prende hace días\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.source").value("UNAVAILABLE"));
    }

    @Test
    void classifyRejectsEmptyOrOversizedDescriptions() throws Exception {
        mvc.perform(post(CLASSIFY).contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"corto\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(CLASSIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + "a".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void classifyAnswers429WithRetryAfterWhenTheClientIsOverItsQuota() throws Exception {
        when(classification.classify(any())).thenThrow(new TooManyAiRequestsException(Duration.ofSeconds(90)));

        mvc.perform(post(CLASSIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"La luz de la esquina no prende hace días\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"));
    }

    @Test
    void insightsRequireAuthentication() throws Exception {
        mvc.perform(post(INSIGHTS, UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void citizensCannotRequestInsights() throws Exception {
        mvc.perform(post(INSIGHTS, UUID.randomUUID()).with(as(ModuleRole.CITIZEN))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = ModuleRole.class, names = {"AGENT", "AREA_RESPONSIBLE", "ADMIN"})
    void staffRolesReachInsights(ModuleRole role) throws Exception {
        when(insights.insights(any(), any())).thenReturn(
                new AiTicketInsightsResponse("UNAVAILABLE", null, null, null, List.of()));

        mvc.perform(post(INSIGHTS, UUID.randomUUID()).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
