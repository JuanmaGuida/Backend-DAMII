package com.reclamos.backend.service;

import com.reclamos.backend.repository.TicketSlaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ResolutionSlaMilestoneJob {
    private final TicketSlaRepository slaRepository;
    private final ResolutionSlaMilestoneService milestoneService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${ticket.sla.milestone-scan-delay:60000}")
    public void processPendingMilestones() {
        Instant now = clock.instant();
        slaRepository.findPendingResolutionMilestones(now)
                .forEach(candidate -> milestoneService.processCandidate(
                        candidate.ticketId(), candidate.slaId(), now));
    }
}
