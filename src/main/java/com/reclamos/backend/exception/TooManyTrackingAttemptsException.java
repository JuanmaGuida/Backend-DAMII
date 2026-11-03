package com.reclamos.backend.exception;

import java.time.Duration;

public class TooManyTrackingAttemptsException extends RuntimeException {
    public static final String CODE = "TOO_MANY_ATTEMPTS";

    private final long retryAfterSeconds;

    public TooManyTrackingAttemptsException(Duration retryAfter) {
        super("Demasiados intentos. Intentá de nuevo más tarde.");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
