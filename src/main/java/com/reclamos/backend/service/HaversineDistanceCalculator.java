package com.reclamos.backend.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class HaversineDistanceCalculator {
    static final double EARTH_RADIUS_METERS = 6_371_000;

    public double distanceMeters(BigDecimal latitudeFrom, BigDecimal longitudeFrom,
                                 BigDecimal latitudeTo, BigDecimal longitudeTo) {
        double latitudeFromRadians = Math.toRadians(latitudeFrom.doubleValue());
        double latitudeToRadians = Math.toRadians(latitudeTo.doubleValue());
        double latitudeDelta = latitudeToRadians - latitudeFromRadians;
        double longitudeDelta = Math.toRadians(longitudeTo.doubleValue() - longitudeFrom.doubleValue());

        double haversine = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(latitudeFromRadians) * Math.cos(latitudeToRadians)
                * Math.pow(Math.sin(longitudeDelta / 2), 2);
        haversine = Math.max(0, Math.min(1, haversine));
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}