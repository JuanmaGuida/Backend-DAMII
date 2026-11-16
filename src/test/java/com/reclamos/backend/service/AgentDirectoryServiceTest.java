package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.AgentDirectoryEntry;
import com.reclamos.backend.repository.AgentDirectoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDirectoryServiceTest {
    private final AgentDirectoryRepository repository = mock(AgentDirectoryRepository.class);

    private AgentDirectoryService serviceAt(String instant) {
        return new AgentDirectoryService(repository, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void theMonthIsCountedInBuenosAiresTime() {
        // 1 de diciembre 01:30 UTC todavía es 30 de noviembre en Buenos Aires (UTC-3).
        serviceAt("2026-12-01T01:30:00Z").list();

        verify(repository).list(Instant.parse("2026-11-01T03:00:00Z"), Instant.parse("2026-12-01T03:00:00Z"));
    }

    @Test
    void theMonthRollsOverAtLocalMidnightAndAcrossTheYear() {
        serviceAt("2026-12-31T03:00:00Z").list();

        verify(repository).list(Instant.parse("2026-12-01T03:00:00Z"), Instant.parse("2027-01-01T03:00:00Z"));
    }

    @Test
    void returnsWhatTheRepositoryFound() {
        AgentDirectoryEntry entry = new AgentDirectoryEntry(null, "Ana", "Paz", null, "ADMIN", null, true, 0, 0, 0, 0);
        when(repository.list(Instant.parse("2026-11-01T03:00:00Z"), Instant.parse("2026-12-01T03:00:00Z")))
                .thenReturn(List.of(entry));

        assertThat(serviceAt("2026-11-16T23:00:00Z").list()).containsExactly(entry);
    }
}
