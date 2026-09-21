package com.reclamos.backend.dto.response;

import lombok.Value;

@Value
public class CategoryIndicatorResponse {
    Long categoryId;
    String categoryName;
    long count;
}