package com.reclamos.backend.dto.response;

import lombok.Data;

import java.time.Instant;

@Data
public class ApiErrorResponse {
    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String message;
}
