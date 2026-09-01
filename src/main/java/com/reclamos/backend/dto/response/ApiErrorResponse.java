package com.reclamos.backend.dto.response;

import lombok.Data;

import java.time.Instant;

@Data
public class ApiErrorResponse {
    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String code;
    private final String message;

    public ApiErrorResponse(Instant timestamp, int status, String error, String message) {
        this(timestamp, status, error, null, message);
    }

    public ApiErrorResponse(Instant timestamp, int status, String error, String code, String message) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.code = code;
        this.message = message;
    }
}