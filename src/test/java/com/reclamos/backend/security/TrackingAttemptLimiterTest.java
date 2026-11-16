package com.reclamos.backend.security;

import com.reclamos.backend.config.TrackingRateLimitProperties;
import com.reclamos.backend.exception.TooManyTrackingAttemptsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackingAttemptLimiterTest {
    private static final String TICKET_HASH = "ticket-hash";
    private static final Instant START = Instant.parse("2026-11-03T12:00:00Z");

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

    private TrackingAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        TrackingRateLimitProperties properties = new TrackingRateLimitProperties();
        properties.setMaxFailuresPerClient(3);
        properties.setMaxFailuresPerTicket(2);
        properties.setWindow(Duration.ofMinutes(10));
        limiter = new TrackingAttemptLimiter(properties, new ClientAddressResolver(), clock);
        bindRequestFrom("203.0.113.10");
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void allowsAttemptsBelowTheClientLimit() {
        limiter.recordFailure(null);
        limiter.recordFailure(null);

        assertDoesNotThrow(() -> limiter.assertAllowed(null));
    }

    @Test
    void blocksClientOnceItReachesTheFailureLimit() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(null);
        }

        TooManyTrackingAttemptsException blocked =
                assertThrows(TooManyTrackingAttemptsException.class, () -> limiter.assertAllowed(null));

        assertEquals(600, blocked.getRetryAfterSeconds());
    }

    @Test
    void blockingOneClientDoesNotAffectAnother() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(null);
        }

        bindRequestFrom("203.0.113.11");

        assertDoesNotThrow(() -> limiter.assertAllowed(null));
    }

    @Test
    void releasesTheClientWhenTheOldestFailureLeavesTheWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(null);
            now.set(now.get().plusSeconds(60));
        }
        assertThrows(TooManyTrackingAttemptsException.class, () -> limiter.assertAllowed(null));

        now.set(START.plus(Duration.ofMinutes(10)).plusSeconds(1));

        assertDoesNotThrow(() -> limiter.assertAllowed(null));
    }

    @Test
    void blocksTicketAcrossDifferentClients() {
        limiter.recordFailure(TICKET_HASH);
        bindRequestFrom("203.0.113.11");
        limiter.recordFailure(TICKET_HASH);

        bindRequestFrom("203.0.113.12");

        assertThrows(TooManyTrackingAttemptsException.class, () -> limiter.assertAllowed(TICKET_HASH));
        assertDoesNotThrow(() -> limiter.assertAllowed("another-ticket-hash"));
        assertDoesNotThrow(() -> limiter.assertAllowed(null));
    }

    @Test
    void failuresWithoutTicketKeyNeverBlockATicket() {
        for (int i = 0; i < 2; i++) {
            limiter.recordFailure(null);
        }
        bindRequestFrom("203.0.113.11");

        assertDoesNotThrow(() -> limiter.assertAllowed(TICKET_HASH));
    }

    @Test
    void ignoresRequestsMadeOutsideAnHttpRequest() {
        RequestContextHolder.resetRequestAttributes();

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(null);
        }

        assertDoesNotThrow(() -> limiter.assertAllowed(null));
    }

    @Test
    void usesForwardedAddressBehindPrivateProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(null);
        }

        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setRemoteAddr("172.18.0.5");
        other.addHeader("X-Forwarded-For", "198.51.100.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(other));

        assertDoesNotThrow(() -> limiter.assertAllowed(null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThrows(TooManyTrackingAttemptsException.class, () -> limiter.assertAllowed(null));
    }

    private void bindRequestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
