package com.reclamos.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketPublicIdGeneratorTest {
    private final JdbcTemplate database = mock(JdbcTemplate.class);
    private final TicketPublicIdGenerator generator = new TicketPublicIdGenerator(database);

    @Test
    void formatsPrefixUtcYearMinimumPaddingAndValuesBeyondSixDigits() {
        when(database.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(1L, 999_999L, 1_000_000L);
        Instant creationInstant = Instant.parse("2026-09-11T15:00:00Z");

        assertEquals("TK-2026-000001", generator.generate(creationInstant));
        assertEquals("TK-2026-999999", generator.generate(creationInstant));
        assertEquals("TK-2026-1000000", generator.generate(creationInstant));
    }

    @Test
    void yearChangesWithoutResettingTheGlobalSequence() {
        when(database.queryForObject(anyString(), eq(Long.class))).thenReturn(123L, 124L);
        Clock endOf2026 = Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC);
        Clock startOf2027 = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);

        assertEquals("TK-2026-000123", generator.generate(endOf2026.instant()));
        assertEquals("TK-2027-000124", generator.generate(startOf2027.instant()));
    }
}
