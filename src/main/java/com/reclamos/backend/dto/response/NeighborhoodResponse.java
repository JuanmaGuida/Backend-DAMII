package com.reclamos.backend.dto.response;

import lombok.Data;

import java.util.UUID;

@Data
public class NeighborhoodResponse {
    private UUID id;
    private String name;
    private Integer population;
}