package com.reclamos.backend.exception;

import java.time.Duration;

/** Se superó el máximo de intentos fallidos de login/refresh (DDA2-259). Se responde 429 + Retry-After. */
public class TooManyLoginAttemptsException extends RuntimeException {
    public static final String CODE = "TOO_MANY_ATTEMPTS";

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Demasiados intentos. Intentá de nuevo más tarde.");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
