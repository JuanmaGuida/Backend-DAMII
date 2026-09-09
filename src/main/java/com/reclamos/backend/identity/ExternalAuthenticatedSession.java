package com.reclamos.backend.identity;

import java.time.Instant;
import java.util.Objects;

public record ExternalAuthenticatedSession(
        String token,
        Instant expiresAt,
        ExternalIdentityProfile profile
) {
    public ExternalAuthenticatedSession {
        Objects.requireNonNull(token, "token is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(profile, "profile is required");
    }

    @Override
    public String toString() {
        return "ExternalAuthenticatedSession[token=[REDACTED], expiresAt=" + expiresAt
                + ", profile=[REDACTED]]";
    }
}
