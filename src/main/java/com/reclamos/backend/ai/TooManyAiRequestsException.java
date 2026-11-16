package com.reclamos.backend.ai;

import java.time.Duration;

public class TooManyAiRequestsException extends RuntimeException {
    public static final String CODE = "AI_RATE_LIMITED";

    private final long retryAfterSeconds;

    public TooManyAiRequestsException(Duration retryAfter) {
        super("Demasiadas consultas de ayuda automática. Intentá de nuevo más tarde.");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
