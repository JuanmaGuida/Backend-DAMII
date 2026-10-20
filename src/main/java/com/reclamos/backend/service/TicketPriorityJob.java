package com.reclamos.backend.service;

import com.reclamos.backend.config.PriorityProperties;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketPriorityJob {
    private final TicketRepository tickets;
    private final TicketPriorityService priorities;
    private final PriorityProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${ticket.priority.scan-delay:300000}",
            initialDelayString = "${ticket.priority.scan-delay:300000}")
    public void recalculatePriorities() {
        List<UUID> upper = tickets.findPriorityScanUpperBound(PageRequest.of(0, 1));
        if (upper.isEmpty()) return;
        UUID after = new UUID(0, 0);
        Instant now = clock.instant();
        while (true) {
            List<UUID> batch = tickets.findPriorityCandidates(TicketPriorityService.ACTIVE_STATUSES,
                    after, upper.getFirst(), PageRequest.of(0, properties.getBatchSize()));
            if (batch.isEmpty()) return;
            for (UUID id : batch) {
                try {
                    priorities.processCandidate(id, now);
                } catch (RuntimeException failure) {
                    log.error("No se pudo recalcular la prioridad del ticket {}", id, failure);
                }
            }
            after = batch.getLast();
        }
    }
}
