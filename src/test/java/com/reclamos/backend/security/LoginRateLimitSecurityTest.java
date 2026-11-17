package com.reclamos.backend.security;

import com.reclamos.backend.config.LoginRateLimitProperties;
import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.controller.AuthController;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.AuthenticatedSession;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDA2-259 (SEG-06): la fuerza bruta contra {@code POST /api/auth/login} termina en 429 con Retry-After.
 * Usa el limiter real y los umbrales por defecto (15 por cliente, 10 por usuario); sólo el servicio de
 * autenticación está simulado. Cada test usa direcciones propias para no compartir cupo.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class,
        LoginRateLimitSecurityTest.LimiterConfiguration.class})
class LoginRateLimitSecurityTest {
    private static final String GOOD_USER = "agent@example.test";
    private static final String GOOD_PASSWORD = "login-rate-limit-good-password";
    private static final AuthenticatedIdentity AGENT = new AuthenticatedIdentity(
            "m1-dev-agent", UUID.fromString("10000000-0000-0000-0000-000000000002"),
            "Agente de prueba", null, ModuleRole.AGENT);

    @TestConfiguration
    static class LimiterConfiguration {
        @Bean
        LoginAttemptLimiter loginAttemptLimiter() {
            return new LoginAttemptLimiter(new LoginRateLimitProperties(), new ClientAddressResolver(),
                    Clock.systemUTC());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void fiftyConsecutiveInvalidLoginsEndInTooManyRequestsWithRetryAfter() throws Exception {
        when(authService.authenticate(anyString(), anyString())).thenReturn(Optional.empty());
        LoginRateLimitProperties defaults = new LoginRateLimitProperties();

        List<Integer> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < 50; attempt++) {
            // Usuarios distintos: el que corta acá es el límite por cliente.
            MvcResult result = login("brute-force-" + attempt + "@example.test", "wrong-password", "198.51.100.10");
            statuses.add(result.getResponse().getStatus());
            if (result.getResponse().getStatus() == 429) {
                assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After"))).isPositive();
            }
        }

        int allowed = defaults.getMaxFailuresPerClient();
        assertThat(statuses.subList(0, allowed)).containsOnly(401);
        assertThat(statuses.subList(allowed, 50)).containsOnly(429);
        // El proveedor de identidad no vio ninguno de los intentos bloqueados.
        verify(authService, times(allowed)).authenticate(anyString(), anyString());
    }

    @Test
    void theBlockedResponseIsCanonicalAndNotCacheable() throws Exception {
        when(authService.authenticate(anyString(), anyString())).thenReturn(Optional.empty());
        for (int attempt = 0; attempt < new LoginRateLimitProperties().getMaxFailuresPerClient(); attempt++) {
            login("shape-" + attempt + "@example.test", "wrong-password", "198.51.100.11");
        }

        mockMvc.perform(loginRequest("shape-final@example.test", "wrong-password", "198.51.100.11"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.message").value("Demasiados intentos. Intentá de nuevo más tarde."))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aBlockedClientIsNotToldWhetherItsCredentialsWereCorrect() throws Exception {
        when(authService.authenticate(anyString(), anyString())).thenReturn(Optional.empty());
        when(authService.authenticate(GOOD_USER, GOOD_PASSWORD)).thenReturn(Optional.of(session()));
        for (int attempt = 0; attempt < new LoginRateLimitProperties().getMaxFailuresPerClient(); attempt++) {
            login("blocked-" + attempt + "@example.test", "wrong-password", "198.51.100.12");
        }

        // Credenciales correctas desde el origen bloqueado: 429, y ni siquiera se evalúan.
        assertThat(login(GOOD_USER, GOOD_PASSWORD, "198.51.100.12").getResponse().getStatus()).isEqualTo(429);
        verify(authService, never()).authenticate(GOOD_USER, GOOD_PASSWORD);
        // Las mismas credenciales desde otro origen funcionan: el bloqueo no es global.
        assertThat(login(GOOD_USER, GOOD_PASSWORD, "198.51.100.13").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void oneAccountAttackedFromManyAddressesIsBlockedByUsername() throws Exception {
        when(authService.authenticate(anyString(), anyString())).thenReturn(Optional.empty());
        int usernameMax = new LoginRateLimitProperties().getMaxFailuresPerUsername();
        for (int attempt = 0; attempt < usernameMax; attempt++) {
            assertThat(login("victim@example.test", "guess-" + attempt, "198.51.100." + (100 + attempt))
                    .getResponse().getStatus()).isEqualTo(401);
        }

        // Una dirección que nunca falló tampoco puede seguir probando esa cuenta...
        assertThat(login("victim@example.test", "guess-final", "198.51.100.200").getResponse().getStatus())
                .isEqualTo(429);
        // ...pero sí puede entrar otra persona desde esa misma dirección.
        assertThat(login("someone.else@example.test", "wrong-password", "198.51.100.200").getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void successfulAndMalformedLoginsDoNotConsumeTheBudget() throws Exception {
        when(authService.authenticate(GOOD_USER, GOOD_PASSWORD)).thenReturn(Optional.of(session()));
        for (int attempt = 0; attempt < 40; attempt++) {
            assertThat(login(GOOD_USER, GOOD_PASSWORD, "198.51.100.14").getResponse().getStatus()).isEqualTo(200);
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")
                            .with(from("198.51.100.14")))
                    .andExpect(status().isBadRequest());
        }
    }

    private MvcResult login(String username, String password, String clientAddress) throws Exception {
        return mockMvc.perform(loginRequest(username, password, clientAddress)).andReturn();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
            String username, String password, String clientAddress) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .with(from(clientAddress));
    }

    private static RequestPostProcessor from(String clientAddress) {
        return request -> {
            request.setRemoteAddr(clientAddress);
            return request;
        };
    }

    private static AuthenticatedSession session() {
        return new AuthenticatedSession("opaque-test-token", Instant.parse("2026-11-16T20:00:00Z"), AGENT);
    }
}
