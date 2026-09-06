package com.reclamos.backend.service;

import com.reclamos.backend.entity.Risk;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class RiskScalePolicy {
    static final int MAX_SCORE = 100;
    static final int MEDIUM_THRESHOLD = 25;
    static final int HIGH_THRESHOLD = 50;
    static final int CRITICAL_THRESHOLD = 75;

    public int baseScore(Risk risk) {
        Objects.requireNonNull(risk, "risk");
        return switch (risk) {
            case LOW -> 0;
            case MEDIUM -> MEDIUM_THRESHOLD;
            case HIGH -> HIGH_THRESHOLD;
            case CRITICAL -> CRITICAL_THRESHOLD;
        };
    }

    public int cap(int score) {
        return Math.max(0, Math.min(MAX_SCORE, score));
    }

    public Risk classify(int score) {
        int cappedScore = cap(score);
        if (cappedScore < MEDIUM_THRESHOLD) {
            return Risk.LOW;
        }
        if (cappedScore < HIGH_THRESHOLD) {
            return Risk.MEDIUM;
        }
        if (cappedScore < CRITICAL_THRESHOLD) {
            return Risk.HIGH;
        }
        return Risk.CRITICAL;
    }
}
