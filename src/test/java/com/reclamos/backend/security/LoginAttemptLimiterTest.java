package com.reclamos.backend.security;

import com.reclamos.backend.config.LoginRateLimitProperties;
import com.reclamos.backend.exception.TooManyLoginAttemptsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DDA2-259 (SEG-06): ventana deslizante por cliente y por usuario, sin guardar el usuario en claro. */
class LoginAttemptLimiterTest {
    private static final Instant START = Instant.parse("2026-11-16T12:00:00Z");
    private static final int CLIENT_MAX = 3;
    private static final int USERNAME_MAX = 2;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setMaxFailuresPerClient(CLIENT_MAX);
        properties.setMaxFailuresPerUsername(USERNAME_MAX);
        properties.setWindow(WINDOW);
        limiter = new LoginAttemptLimiter(properties, new ClientAddressResolver(), clock);
    }

    @Test
    void clientIsBlockedAfterTheMaximumAndRetryAfterMatchesTheWindow() {
        MockHttpServletRequest attacker = from("203.0.113.10");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.assertAllowed(attacker, "user-" + attempt + "@example.test");
            limiter.recordFailure(attacker, "user-" + attempt + "@example.test");
        }

        TooManyLoginAttemptsException blocked = assertThrows(TooManyLoginAttemptsException.class,
                () -> limiter.assertAllowed(attacker, "another@example.test"));

        assertEquals(WINDOW.toSeconds(), blocked.getRetryAfterSeconds());
        assertDoesNotThrow(() -> limiter.assertAllowed(from("203.0.113.11"), "fresh@example.test"));
    }

    @Test
    void blockedClientIsAlsoBlockedWithoutUsernameSoRefreshIsCovered() {
        MockHttpServletRequest attacker = from("203.0.113.12");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.recordFailure(attacker, null);
        }

        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.assertAllowed(attacker, null));
    }

    @Test
    void usernameIsBlockedAcrossClientsAndNormalized() {
        limiter.recordFailure(from("203.0.113.20"), "Victim@Example.test");
        limiter.recordFailure(from("203.0.113.21"), "  victim@example.test ");

        MockHttpServletRequest freshClient = from("203.0.113.22");
        assertThrows(TooManyLoginAttemptsException.class,
                () -> limiter.assertAllowed(freshClient, "VICTIM@example.test"));
        assertDoesNotThrow(() -> limiter.assertAllowed(freshClient, "someone.else@example.test"));
    }

    @Test
    void blockLiftsWhenTheOldestFailureLeavesTheWindow() {
        MockHttpServletRequest attacker = from("203.0.113.30");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.recordFailure(attacker, null);
        }

        now.set(START.plus(WINDOW).minusSeconds(1));
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.assertAllowed(attacker, null));

        now.set(START.plus(WINDOW));
        assertDoesNotThrow(() -> limiter.assertAllowed(attacker, null));
    }

    @Test
    void successClearsTheUsernameButNotTheClient() {
        MockHttpServletRequest owner = from("203.0.113.40");
        limiter.recordFailure(owner, "me@example.test");
        limiter.recordFailure(owner, "me@example.test");

        limiter.recordSuccess("me@example.test");

        // El cupo del usuario se restableció: otro cliente puede intentar con esa cuenta.
        assertDoesNotThrow(() -> limiter.assertAllowed(from("203.0.113.41"), "me@example.test"));
        // El del cliente no: con un solo fallo más alcanza el máximo (quien tenga una cuenta propia no
        // puede resetear su cupo y seguir probando contraseñas ajenas).
        limiter.recordFailure(owner, "other@example.test");
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.assertAllowed(owner, "me@example.test"));
    }

    @Test
    void theUsernameIsNeverKeptInClearText() {
        limiter.recordFailure(from("203.0.113.50"), "private.person@example.test");

        Map<?, ?> state = (Map<?, ?>) ReflectionTestUtils.getField(limiter, "failures");
        assertThat(state).isNotEmpty();
        assertThat(state.keySet().stream().map(String::valueOf))
                .noneMatch(key -> key.contains("private") || key.contains("@"));
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }
}
