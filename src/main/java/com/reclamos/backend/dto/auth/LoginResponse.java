package com.reclamos.backend.dto.auth;

import com.reclamos.backend.identity.AuthenticatedSession;

import java.time.Instant;

public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        IdentityResponse identity
) {
    public static LoginResponse from(AuthenticatedSession session) {
        return new LoginResponse(
                session.token(),
                "Bearer",
                session.expiresAt(),
                IdentityResponse.from(session.identity())
        );
    }

    @Override
    public String toString() {
        return "LoginResponse[token=[REDACTED], tokenType=" + tokenType
                + ", expiresAt=" + expiresAt + ", identity=[REDACTED]]";
    }
}
