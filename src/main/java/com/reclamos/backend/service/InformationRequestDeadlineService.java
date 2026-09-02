package com.reclamos.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class InformationRequestDeadlineService {
    private final Clock clock;
    private final Duration responseDuration;

    public InformationRequestDeadlineService(Clock clock,
                                             @Value("${ticket.information-request.response-duration:72h}") Duration responseDuration) {
        if (responseDuration.isNegative() || responseDuration.isZero()) {
            throw new IllegalArgumentException("La duración de respuesta debe ser positiva");
        }
        this.clock = clock;
        this.responseDuration = responseDuration;
    }

    public Instant now() { return clock.instant(); }
    public Instant calculateDueAt(Instant requestedAt) { return requestedAt.plus(responseDuration); }
    public boolean isExpired(Instant dueAt) { return !clock.instant().isBefore(dueAt); }
}