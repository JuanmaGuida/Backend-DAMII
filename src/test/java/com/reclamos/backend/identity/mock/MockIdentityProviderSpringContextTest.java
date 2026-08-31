package com.reclamos.backend.identity.mock;

import com.reclamos.backend.identity.AuthenticatedSession;
import com.reclamos.backend.identity.IdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(
        classes = MockIdentityProvider.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("dev")
class MockIdentityProviderSpringContextTest {
    @Autowired
    private IdentityProvider identityProvider;

    @Autowired
    private Environment environment;

    @Test
    void devContextCreatesIdentityProviderWithConfiguredSessionTtl() {
        assertEquals("PT8H", environment.getRequiredProperty("app.identity.mock.session-ttl"));
        assertInstanceOf(MockIdentityProvider.class, identityProvider);

        Instant beforeLogin = Instant.now();
        AuthenticatedSession session = identityProvider.authenticate(
                MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD
        ).orElseThrow();
        Instant afterLogin = Instant.now();

        assertFalse(session.expiresAt().isBefore(beforeLogin.plus(Duration.ofHours(8))));
        assertFalse(session.expiresAt().isAfter(afterLogin.plus(Duration.ofHours(8))));
    }
}
