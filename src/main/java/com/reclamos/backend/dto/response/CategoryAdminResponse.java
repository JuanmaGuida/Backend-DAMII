package com.reclamos.backend.dto.response;

import lombok.Data;

import java.time.Instant;

@Data
public class CategoryAdminResponse {
    private Long id;
    private String name;
    private String description;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
