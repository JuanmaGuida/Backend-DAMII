package com.reclamos.backend.dto.response;

import java.util.UUID;

import lombok.Value;

@Value
public class NeighborhoodIndicatorResponse {
    UUID neighborhoodId;
    String neighborhoodName;
    long count;
}