package com.reclamos.backend.service;

import com.reclamos.backend.entity.Risk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskScalePolicyTest {
    private final RiskScalePolicy policy = new RiskScalePolicy();

    @Test
    void exposesApprovedBaseScoresWithoutDependingOnEnumOrdinal() {
        assertEquals(0, policy.baseScore(Risk.LOW));
        assertEquals(25, policy.baseScore(Risk.MEDIUM));
        assertEquals(50, policy.baseScore(Risk.HIGH));
        assertEquals(75, policy.baseScore(Risk.CRITICAL));
    }

    @Test
    void classifiesEveryApprovedBoundary() {
        assertEquals(Risk.LOW, policy.classify(0));
        assertEquals(Risk.LOW, policy.classify(24));
        assertEquals(Risk.MEDIUM, policy.classify(25));
        assertEquals(Risk.MEDIUM, policy.classify(49));
        assertEquals(Risk.HIGH, policy.classify(50));
        assertEquals(Risk.HIGH, policy.classify(74));
        assertEquals(Risk.CRITICAL, policy.classify(75));
        assertEquals(Risk.CRITICAL, policy.classify(100));
    }

    @Test
    void capsScoresAtScaleLimits() {
        assertEquals(0, policy.cap(-1));
        assertEquals(100, policy.cap(101));
        assertEquals(100, policy.cap(Integer.MAX_VALUE));
    }
}
