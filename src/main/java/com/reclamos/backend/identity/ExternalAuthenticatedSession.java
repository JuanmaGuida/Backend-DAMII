package com.reclamos.backend.identity;

import java.time.Instant;
import java.util.Objects;

public record ExternalAuthenticatedSession(
        String token,
        Instant expiresAt,
        ExternalIdentityProfile profile,
        String refreshToken
) {
    public ExternalAuthenticatedSession {
        Objects.requireNonNull(token, "token is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(profile, "profile is required");
    }

    public ExternalAuthenticatedSession(String token, Instant expiresAt, ExternalIdentityProfile profile) {
        this(token, expiresAt, profile, null);
    }

    @Override
    public String toString() {
        return "ExternalAuthenticatedSession[token=[REDACTED], expiresAt=" + expiresAt
                + ", profile=[REDACTED]]";
    }
}
