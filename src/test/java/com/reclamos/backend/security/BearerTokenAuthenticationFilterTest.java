package com.reclamos.backend.security;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.ModuleRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
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
    private final IdentityProvider identityProvider = mock(IdentityProvider.class);
    private final BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(identityProvider);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerCreatesNeutralPrincipalWithoutTokenCredentials() throws Exception {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                "m1-dev-supervisor",
                UUID.fromString("10000000-0000-0000-0000-000000000004"),
                "Supervisor de prueba",
                "M2",
                Set.of(ModuleRole.SUPERVISOR, ModuleRole.AUDITOR)
        );
        when(identityProvider.resolve("valid-token")).thenReturn(Optional.of(identity));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer valid-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.isAuthenticated());
        assertSame(identity, authentication.getPrincipal());
        assertNull(authentication.getCredentials());
        assertEquals(Set.of("ROLE_SUPERVISOR", "ROLE_AUDITOR"), authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void invalidBearerLeavesRequestUnauthenticated() throws Exception {
        when(identityProvider.resolve("invalid-token")).thenReturn(Optional.empty());
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
        verify(identityProvider, never()).resolve(org.mockito.ArgumentMatchers.anyString());
    }
}
