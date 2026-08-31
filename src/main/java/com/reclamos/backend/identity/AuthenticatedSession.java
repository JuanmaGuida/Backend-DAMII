package com.reclamos.backend.identity;

import java.time.Instant;
import java.util.Objects;

public record AuthenticatedSession(
        String token,
        Instant expiresAt,
        AuthenticatedIdentity identity
) {
    public AuthenticatedSession {
        Objects.requireNonNull(token, "token is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(identity, "identity is required");
    }

    @Override
    public String toString() {
        return "AuthenticatedSession[token=[REDACTED], expiresAt=" + expiresAt + ", identity=[REDACTED]]";
    }
}
