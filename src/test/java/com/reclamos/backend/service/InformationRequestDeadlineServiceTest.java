package com.reclamos.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class InformationRequestDeadlineServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InformationRequestDeadlineService service =
            new InformationRequestDeadlineService(clock, Duration.ofHours(48));

    @Test
    void calculatesDeadlineFromRequestedAtUsingConfiguredDurationAndFixedClock() {
        assertEquals(NOW, service.now());
        assertEquals(Instant.parse("2026-01-12T12:00:00Z"), service.calculateDueAt(NOW));
    }

    @Test
    void deadlineIsInclusiveForExpiration() {
        assertFalse(service.isExpired(NOW.plusNanos(1)));
        assertTrue(service.isExpired(NOW));
        assertTrue(service.isExpired(NOW.minusNanos(1)));
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new InformationRequestDeadlineService(clock, Duration.ZERO));
    }
}