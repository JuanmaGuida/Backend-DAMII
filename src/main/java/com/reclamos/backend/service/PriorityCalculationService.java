package com.reclamos.backend.service;

import com.reclamos.backend.config.PriorityProperties;
import com.reclamos.backend.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PriorityCalculationService {
    private final PriorityProperties properties;
    private final SlaCalculationService slaCalculation;

    public BigDecimal affectedScore(int count) {
        if (count < 0) throw new IllegalArgumentException("La cantidad de afectados no puede ser negativa");
        BigDecimal score = BigDecimal.ZERO;
        for (PriorityProperties.Band band : properties.getAffectedBands()) {
            if (count < band.getMinimumCount()) break;
            score = band.getScore();
        }
        return score;
    }

    public AgeFraction age(TicketSla sla, Instant now) {
        if (sla == null || sla.getSlaType() != SlaType.RESOLUTION || sla.getCompletedAt() != null)
            return new AgeFraction(0, 1);
        Instant end = sla.getPausedAt() != null && sla.getPausedAt().isBefore(now) ? sla.getPausedAt() : now;
        if (end.isBefore(sla.getStartedAt())) return new AgeFraction(0, 1);
        // Rebuild the original duration from this cycle's policy and start. Adjusted
        // deadlines include pauses and may carry second-level rounding from resume.
        Instant originalDueAt = slaCalculation.calculateDueAt(sla.getStartedAt(), sla.getPolicy());
        long duration = slaCalculation.effectiveSecondsBetween(sla.getStartedAt(), originalDueAt, sla.getPolicy());
        long elapsed = slaCalculation.effectiveSecondsBetween(sla.getStartedAt(), end, sla.getPolicy())
                - sla.getTotalPausedSeconds();
        if (duration <= 0) throw new IllegalStateException("El ciclo SLA no tiene duración efectiva positiva");
        if (sla.getPausedAt() == null && !now.isBefore(sla.getDueAt())) elapsed = duration;
        return new AgeFraction(Math.clamp(elapsed, 0, duration), duration);
    }

    public Priority calculate(int riskScore, int affectedCount, AgeFraction age, Priority minimum) {
        if (riskScore < 0 || riskScore > 100) throw new IllegalArgumentException("Riesgo fuera de rango");
        BigDecimal base = properties.getWRisk().multiply(BigDecimal.valueOf(riskScore, 2))
                .add(properties.getWAffected().multiply(affectedScore(affectedCount)));
        // Compare rational scores by cross multiplication; no rounded division at thresholds.
        BigDecimal denominator = BigDecimal.valueOf(age.duration());
        BigDecimal numerator = base.multiply(denominator)
                .add(properties.getWAge().multiply(BigDecimal.valueOf(age.elapsed())));
        Priority calculated = numerator.compareTo(properties.getCriticalThreshold().multiply(denominator)) >= 0
                ? Priority.CRITICAL
                : numerator.compareTo(properties.getHighThreshold().multiply(denominator)) >= 0 ? Priority.HIGH
                : numerator.compareTo(properties.getMediumThreshold().multiply(denominator)) >= 0 ? Priority.MEDIUM
                : Priority.LOW;
        return max(minimum, calculated);
    }

    public static Priority max(Priority first, Priority second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    public record AgeFraction(long elapsed, long duration) {
        public AgeFraction {
            if (duration <= 0 || elapsed < 0 || elapsed > duration)
                throw new IllegalArgumentException("Fracción de antigüedad inválida");
        }
        public BigDecimal score() {
            return BigDecimal.valueOf(elapsed).divide(BigDecimal.valueOf(duration), 18, RoundingMode.DOWN);
        }
    }
}
