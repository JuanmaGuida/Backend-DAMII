package com.reclamos.backend.service;

import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ResolutionConfirmationJob {
    private final TicketRepository ticketRepository;
    private final ResolutionConfirmationTimeoutService timeoutService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${ticket.resolution.confirmation-scan-delay:60000}")
    public void closeExpiredResolutions() {
        Instant now = clock.instant();
        ticketRepository.findExpiredResolutionConfirmationIds(now)
                .forEach(ticketId -> timeoutService.closeIfConfirmationExpired(ticketId, now));
    }
}