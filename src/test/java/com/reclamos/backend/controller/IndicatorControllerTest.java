package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.IndicatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IndicatorController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class IndicatorControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean IndicatorService service;
    @MockitoBean AuthService authService;

    private static UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken("user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void categoryEndpointBindsEveryCombinedFilter() throws Exception {
        UUID neighborhood = UUID.randomUUID();
        when(service.categories(any())).thenReturn(List.of(new CategoryIndicatorResponse(1L, "Ambiente", 3)));
        mockMvc.perform(get("/api/indicators/categories")
                        .param("categoryId", "1").param("neighborhoodId", neighborhood.toString())
                        .param("priority", "HIGH").param("responsibleAreaId", "M6")
                        .param("status", "IN_PROGRESS").param("slaType", "RESOLUTION")
                        .param("slaStatus", "BREACHED").with(authentication(auth("AGENT"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].categoryName").value("Ambiente"))
                .andExpect(jsonPath("$[0].count").value(3));
        verify(service).categories(new IndicatorFilter(1L, neighborhood, Priority.HIGH, "M6",
                TicketStatus.IN_PROGRESS, SlaType.RESOLUTION, SlaStatus.BREACHED));
    }

    @Test
    void allEndpointsSerializeResponsesAndEmptyListIsAnArray() throws Exception {
        when(service.neighborhoods(any())).thenReturn(List.of(new NeighborhoodIndicatorResponse(
                UUID.fromString("10000000-0000-0000-0000-000000000001"), "Centro", 2)));
        when(service.priorities(any())).thenReturn(List.of(new PriorityIndicatorResponse(Priority.HIGH, 4)));
        when(service.areas(any())).thenReturn(List.of(new AreaIndicatorResponse("M2", 5)));
        when(service.sla(any())).thenReturn(List.of());
        var agent = authentication(auth("AGENT"));
        mockMvc.perform(get("/api/indicators/neighborhoods").with(agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].neighborhoodName").value("Centro"));
        mockMvc.perform(get("/api/indicators/priorities").with(authentication(auth("AGENT"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].priority").value("HIGH"));
        mockMvc.perform(get("/api/indicators/areas").with(authentication(auth("ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].responsibleAreaId").value("M2"));
        mockMvc.perform(get("/api/indicators/sla").with(authentication(auth("ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void securityAllowsOnlyAgentAndAdmin() throws Exception {
        mockMvc.perform(get("/api/indicators/categories")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mockMvc.perform(get("/api/indicators/categories").with(authentication(auth("CITIZEN"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/indicators/categories").with(authentication(auth("AREA_RESPONSIBLE"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}