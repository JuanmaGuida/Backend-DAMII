package com.reclamos.backend.dto.response;

import lombok.Data;

@Data
public class SubcategoryAdminResponse {
    private Long id;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String description;
    private boolean active;
}
