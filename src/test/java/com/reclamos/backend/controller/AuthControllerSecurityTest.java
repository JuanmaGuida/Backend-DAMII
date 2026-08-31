package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.auth.LoginRequest;
import com.reclamos.backend.dto.auth.LoginResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.AuthenticatedSession;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class AuthControllerSecurityTest {
    private static final String TOKEN = "opaque-test-token";
    private static final AuthenticatedIdentity AGENT = new AuthenticatedIdentity(
            "m1-dev-agent",
            UUID.fromString("10000000-0000-0000-0000-000000000002"),
            "Agente de prueba",
            "M2",
            Set.of(ModuleRole.AGENT)
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IdentityProvider identityProvider;

    @Test
    void loginIsPublicAndCsrfDoesNotBlockIt() throws Exception {
        when(identityProvider.authenticate("agent@example.test", "AgentDev!2026"))
                .thenReturn(Optional.of(new AuthenticatedSession(
                        TOKEN,
                        Instant.parse("2026-08-30T20:00:00Z"),
                        AGENT
                )));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"agent@example.test","password":"AgentDev!2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(TOKEN))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.identity.subjectId").value("m1-dev-agent"))
                .andExpect(jsonPath("$.identity.citizenId")
                        .value("10000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.identity.areaId").value("M2"))
                .andExpect(jsonPath("$.identity.roles[0]").value("AGENT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("AgentDev!2026"))));
    }

    @Test
    void publicAuthContractsRedactPasswordAndTokenFromToString() {
        AuthenticatedSession session = new AuthenticatedSession(
                TOKEN,
                Instant.parse("2026-08-30T20:00:00Z"),
                AGENT
        );

        String requestDescription = new LoginRequest("agent@example.test", "AgentDev!2026").toString();
        assertFalse(requestDescription.contains("agent@example.test"));
        assertFalse(requestDescription.contains("AgentDev!2026"));
        assertFalse(session.toString().contains(TOKEN));
        String responseDescription = LoginResponse.from(session).toString();
        assertFalse(responseDescription.contains(TOKEN));
        assertFalse(responseDescription.contains(AGENT.subjectId()));
    }

    @Test
    void missingRequiredLoginFieldsReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownUserAndWrongPasswordHaveIdenticalExternalFailure() throws Exception {
        when(identityProvider.authenticate("unknown@example.test", "wrong")).thenReturn(Optional.empty());
        when(identityProvider.authenticate("agent@example.test", "wrong")).thenReturn(Optional.empty());

        MvcResult unknownUser = invalidLogin("unknown@example.test", "wrong");
        MvcResult wrongPassword = invalidLogin("agent@example.test", "wrong");

        assertEquals(401, unknownUser.getResponse().getStatus());
        assertEquals(unknownUser.getResponse().getContentAsString(), wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void meWithoutTokenReturnsUnauthorizedWithoutFormRedirect() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void invalidOrExpiredTokenReturnsTheSameUnauthorizedResponse() throws Exception {
        when(identityProvider.resolve("invalid")).thenReturn(Optional.empty());
        when(identityProvider.resolve("expired")).thenReturn(Optional.empty());

        MvcResult invalid = meWithToken("invalid").andExpect(status().isUnauthorized()).andReturn();
        MvcResult expired = meWithToken("expired").andExpect(status().isUnauthorized()).andReturn();

        assertEquals(invalid.getResponse().getContentAsString(), expired.getResponse().getContentAsString());
    }

    @Test
    void validBearerPopulatesPrincipalAndAuthoritiesForMe() throws Exception {
        when(identityProvider.resolve(TOKEN)).thenReturn(Optional.of(AGENT));

        meWithToken(TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value("m1-dev-agent"))
                .andExpect(jsonPath("$.citizenId").value("10000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.displayName").value("Agente de prueba"))
                .andExpect(jsonPath("$.areaId").value("M2"))
                .andExpect(jsonPath("$.roles[0]").value("AGENT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ROLE_AGENT"))));
    }

    @Test
    void httpBasicIsNotAnAlternativeAuthenticationMechanism() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(httpBasic("user", "password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void healthEndpointIsNotBlockedBySecurity() throws Exception {
        int status = mockMvc.perform(get("/actuator/health"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertNotEquals(401, status);
        assertNotEquals(403, status);
    }

    @Test
    void invalidBearerDoesNotBlockUnrelatedPublicEndpoints() throws Exception {
        when(identityProvider.resolve("invalid")).thenReturn(Optional.empty());

        int status = mockMvc.perform(get("/api/categories")
                        .header("Authorization", "Bearer invalid"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertNotEquals(401, status);
        assertNotEquals(403, status);
    }

    private MvcResult invalidLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Usuario o contraseña inválidos"))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions meWithToken(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
    }
}
