package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.response.AgentDirectoryEntry;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AgentDirectoryService;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAgentController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
class AdminAgentControllerSecurityTest {
    static final String AGENTS = "/api/admin/agents";

    @Autowired MockMvc mvc;
    @MockitoBean AgentDirectoryService service;
    @MockitoBean AuthService authService;

    static RequestPostProcessor as(ModuleRole role) {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(role.name(), UUID.randomUUID(), role.name(),
                role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null, role);
        return authentication(new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @Test
    void anonymousCallersAreRejectedBeforeTheDirectoryIsRead() throws Exception {
        mvc.perform(get(AGENTS)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = ModuleRole.class, names = {"CITIZEN", "AGENT", "AREA_RESPONSIBLE"})
    void onlyAdminsCanReadTheStaffDirectory(ModuleRole role) throws Exception {
        mvc.perform(get(AGENTS).with(as(role))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminGetsTheDirectoryWithoutCaching() throws Exception {
        UUID actorId = UUID.randomUUID();
        when(service.list()).thenReturn(List.of(new AgentDirectoryEntry(actorId, "Laura", "Martínez",
                "laura@example.org", "AGENT", null, true, 4, 2, 3, 1)));

        mvc.perform(get(AGENTS).with(as(ModuleRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].actorId").value(actorId.toString()))
                .andExpect(jsonPath("$[0].firstName").value("Laura"))
                .andExpect(jsonPath("$[0].role").value("AGENT"))
                .andExpect(jsonPath("$[0].areaId").doesNotExist())
                .andExpect(jsonPath("$[0].activeTickets").value(4))
                .andExpect(jsonPath("$[0].resolvedThisMonth").value(2))
                .andExpect(jsonPath("$[0].slaMet").value(3))
                .andExpect(jsonPath("$[0].slaBreached").value(1))
                .andExpect(jsonPath("$[0].phone").doesNotExist())
                .andExpect(jsonPath("$[0].citizenId").doesNotExist());
    }
}
