package com.reclamos.backend.service;

import com.reclamos.backend.entity.Risk;

import java.util.Objects;

public record RiskAssessment(int score, Risk calculatedRisk) {
    public RiskAssessment {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("El score de riesgo debe estar entre 0 y 100");
        }
        Objects.requireNonNull(calculatedRisk, "calculatedRisk");
    }
}
