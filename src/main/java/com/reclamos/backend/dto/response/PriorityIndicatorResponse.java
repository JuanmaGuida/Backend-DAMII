package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.Priority;
import lombok.Value;

@Value
public class PriorityIndicatorResponse {
    Priority priority;
    long count;
}