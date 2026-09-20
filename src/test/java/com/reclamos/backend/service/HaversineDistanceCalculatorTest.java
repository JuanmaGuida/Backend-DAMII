package com.reclamos.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HaversineDistanceCalculatorTest {
    private final HaversineDistanceCalculator calculator = new HaversineDistanceCalculator();

    @Test
    void samePointHasZeroDistance() {
        assertEquals(0, calculator.distanceMeters(
                new BigDecimal("-34.603722"), new BigDecimal("-58.381592"),
                new BigDecimal("-34.603722"), new BigDecimal("-58.381592")), 0.000001);
    }

    @Test
    void calculatesKnownDistanceOnEarthSurface() {
        // Un grado de longitud sobre el ecuador equivale aproximadamente a 111,195 m.
        double distance = calculator.distanceMeters(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);

        assertEquals(111_194.93, distance, 0.02);
    }
}