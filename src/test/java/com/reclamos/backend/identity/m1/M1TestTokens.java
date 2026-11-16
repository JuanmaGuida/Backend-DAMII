package com.reclamos.backend.identity.m1;

import com.reclamos.backend.identity.IdentityActorType;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Public, deterministic test material. Never suitable for a persistent environment. */
public final class M1TestTokens {
    public static final String ORIGIN = "https://m2.example.test";
    public static final String BASE = "https://m1.example.test";
    public static final UUID CITIZEN = UUID.fromString("d04cbd7f-1952-47ae-81b9-977654f70101");
    public static final UUID EMPLOYEE = UUID.fromString("d04cbd7f-1952-47ae-81b9-977654f70102");

    private M1TestTokens() { }

    public static byte[] bytes() {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        return bytes;
    }

    public static String encodedKey() { return Base64.getEncoder().encodeToString(bytes()); }

    public static M1Settings settings() {
        return new M1Settings(BASE, encodedKey(), ORIGIN, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    public static Map<String, Object> claims(UUID actor, IdentityActorType type, Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "modulo1-ciudadanos");
        claims.put("sub", "14");
        claims.put("typ", "access");
        claims.put("iat", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(900)));
        claims.put("actorId", actor.toString());
        claims.put("actorType", type.name());
        claims.put("sub_type", type.name());
        claims.put("rol", "ADMINISTRATIVO");
        if (type == IdentityActorType.CIUDADANO) claims.put("citizenId", actor.toString());
        return claims;
    }

    public static String token(Map<String, Object> claims) {
        return token(claims, new SecretKeySpec(bytes(), "HmacSHA256"));
    }

    public static String token(Map<String, Object> claims, SecretKey key) {
        return Jwts.builder().claims(claims).signWith(key, Jwts.SIG.HS256).compact();
    }

    public static String token(UUID actor, IdentityActorType type) {
        return token(claims(actor, type, Instant.now()));
    }
}
