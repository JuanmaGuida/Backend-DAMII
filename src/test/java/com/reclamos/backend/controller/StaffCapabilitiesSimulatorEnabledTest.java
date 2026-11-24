package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffCapabilitiesController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "app.simulator.enabled=true")
class StaffCapabilitiesSimulatorEnabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;

    @Test
    void staffSeesTheSimulatorAsEnabledWhenTheServerHasIt() throws Exception {
        mvc.perform(get("/api/staff/capabilities").with(StaffCapabilitiesControllerSecurityTest.as(ModuleRole.AGENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatorEnabled").value(true));
    }
}
