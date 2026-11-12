package com.reclamos.backend.identity.m1;

import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.IdentitySource;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public final class M1AccessTokenVerifier {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final JwtParser parser;
    private final Clock clock;

    public M1AccessTokenVerifier(M1Settings settings, Clock clock) {
        this.clock = clock;
        parser = Jwts.parser().verifyWith(settings.verificationKey())
                .sig().clear().add(Jwts.SIG.HS256).and()
                .requireIssuer("modulo1-ciudadanos").require("typ", "access")
                .clock(() -> Date.from(clock.instant())).build();
    }

    public Optional<VerifiedAccess> verify(String token) {
        if (token == null || token.isBlank() || token.length() > 16384) return Optional.empty();
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            Instant expiresAt = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            if (expiresAt == null || !expiresAt.isAfter(clock.instant())) return Optional.empty();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) return Optional.empty();
            String type = claims.get("actorType", String.class);
            if (type == null || !type.equals(claims.get("sub_type", String.class))) return Optional.empty();
            String role = claims.get("rol", String.class);
            if (role == null || role.isBlank()) return Optional.empty();
            IdentityActorType actorType = IdentityActorType.valueOf(type);
            UUID actorId = uuid(claims.get("actorId"));
            UUID citizenId = claims.get("citizenId") == null ? null : uuid(claims.get("citizenId"));
            actorType.validate(actorId, citizenId);
            ExternalIdentityProfile identity = new ExternalIdentityProfile(subject, actorId, actorType,
                    IdentitySource.M1, citizenId, null, null, null, null, null, null);
            return Optional.of(new VerifiedAccess(identity, expiresAt));
        } catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    static UUID uuid(Object value) {
        if (!(value instanceof String text) || !UUID_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid external UUID");
        }
        return UUID.fromString(text);
    }

    public record VerifiedAccess(ExternalIdentityProfile identity, Instant expiresAt) {
        @Override public String toString() { return "VerifiedAccess[identity=[REDACTED]]"; }
    }
}
