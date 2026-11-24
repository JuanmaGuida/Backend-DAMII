package com.reclamos.backend.controller;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffCapabilitiesController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
class StaffCapabilitiesControllerSecurityTest {
    static final String CAPABILITIES = "/api/staff/capabilities";

    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;

    static RequestPostProcessor as(ModuleRole role) {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(role.name(), UUID.randomUUID(), role.name(),
                role == ModuleRole.AREA_RESPONSIBLE ? "M2" : null, role);
        return authentication(new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get(CAPABILITIES)).andExpect(status().isUnauthorized());
    }

    @Test
    void citizensCannotReadTheServerCapabilities() throws Exception {
        mvc.perform(get(CAPABILITIES).with(as(ModuleRole.CITIZEN))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = ModuleRole.class, names = {"AGENT", "AREA_RESPONSIBLE", "ADMIN"})
    void staffSeesTheSimulatorAsDisabledByDefault(ModuleRole role) throws Exception {
        mvc.perform(get(CAPABILITIES).with(as(role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.simulatorEnabled").value(false));
    }
}
