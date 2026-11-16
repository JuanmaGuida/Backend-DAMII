package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.AgentDirectoryEntry;
import com.reclamos.backend.repository.AgentDirectoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentDirectoryService {
    /** "Este mes" se cuenta en hora de Buenos Aires, no en UTC. */
    static final ZoneId MONTH_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private final AgentDirectoryRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<AgentDirectoryEntry> list() {
        LocalDate firstDay = LocalDate.ofInstant(clock.instant(), MONTH_ZONE).withDayOfMonth(1);
        Instant monthStart = firstDay.atStartOfDay(MONTH_ZONE).toInstant();
        Instant monthEnd = firstDay.plusMonths(1).atStartOfDay(MONTH_ZONE).toInstant();
        return repository.list(monthStart, monthEnd);
    }
}
