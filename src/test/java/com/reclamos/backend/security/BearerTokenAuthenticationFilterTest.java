package com.reclamos.backend.security;

import com.reclamos.backend.exception.IdentityAccessDeniedException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BearerTokenAuthenticationFilterTest {
    private final AuthService authService = mock(AuthService.class);
    private final JsonMapper json = JsonMapper.builder().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
    private final BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(authService, json);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerCreatesOneAuthorityForTheIdentityRole() throws Exception {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                "m1-dev-agent",
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                "Agente de prueba",
                null,
                ModuleRole.AGENT
        );
        when(authService.resolve("valid-token")).thenReturn(Optional.of(identity));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer valid-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.isAuthenticated());
        assertSame(identity, authentication.getPrincipal());
        assertNull(authentication.getCredentials());
        assertEquals("ROLE_AGENT", authentication.getAuthorities().iterator().next().getAuthority());
        assertEquals(1, authentication.getAuthorities().size());
    }

    @Test
    void invalidBearerLeavesRequestUnauthenticated() throws Exception {
        when(authService.resolve("invalid-token")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer invalid-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingOrMalformedBearerDoesNotConsultProvider() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/categories");
        request.addHeader("Authorization", "Basic ignored");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(authService, never()).resolve(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void restrictedIdentityReceivesProblemDetailsWithoutEnteringTheChain() throws Exception {
        when(authService.resolve("restricted-token")).thenThrow(new IdentityAccessDeniedException());
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer restricted-token");
        request.setQueryString("private=ignore");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var body = json.readTree(response.getContentAsByteArray());
        assertEquals(403, body.path("status").asInt());
        assertEquals("/api/auth/me", body.path("instance").asText());
        assertEquals("FORBIDDEN", body.path("code").asText());
        assertEquals("La cuenta no tiene acceso habilitado a M2", body.path("detail").asText());
        assertEquals(body.path("detail"), body.path("message"));
    }
}
