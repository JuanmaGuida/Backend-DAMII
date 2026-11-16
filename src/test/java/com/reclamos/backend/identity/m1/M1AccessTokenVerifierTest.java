package com.reclamos.backend.identity.m1;

import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.IdentitySource;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class M1AccessTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private final M1AccessTokenVerifier verifier = new M1AccessTokenVerifier(M1TestTokens.settings(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesCitizenWithDecodedSecretAndDoesNotProjectExternalRole() {
        var result = verifier.verify(M1TestTokens.token(claims())).orElseThrow();
        assertThat(result.identity().actorId()).isEqualTo(M1TestTokens.CITIZEN);
        assertThat(result.identity().citizenId()).isEqualTo(M1TestTokens.CITIZEN);
        assertThat(result.identity().subjectId()).isEqualTo("14");
        assertThat(result.identity().identitySource()).isEqualTo(IdentitySource.M1);
        assertThat(result.identity().firstName()).isNull();
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(result.toString()).doesNotContain(M1TestTokens.CITIZEN.toString());
    }

    @Test
    void verifiesEmployeeWithoutInventingCitizenId() {
        var claims = M1TestTokens.claims(M1TestTokens.EMPLOYEE, IdentityActorType.EMPLEADO, NOW);
        var identity = verifier.verify(M1TestTokens.token(claims)).orElseThrow().identity();
        assertThat(identity.actorId()).isEqualTo(M1TestTokens.EMPLOYEE);
        assertThat(identity.actorType()).isEqualTo(IdentityActorType.EMPLEADO);
        assertThat(identity.citizenId()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsInvalidRequiredClaims(Map<String, Object> claims) {
        assertThat(verifier.verify(M1TestTokens.token(claims))).isEmpty();
    }

    static Stream<Map<String, Object>> invalidClaims() {
        return Stream.of("iss", "typ", "exp", "sub", "actorId", "actorType", "citizenId", "sub_type", "rol")
                .map(name -> { var claims = claims(); claims.remove(name); return claims; });
    }

    @Test
    void rejectsExpiredExactlyExpiringRefreshInconsistentAndMalformedIdentifiers() {
        for (var entry : Map.<String, Object>of("iss", "other", "typ", "refresh", "actorId", "14",
                "citizenId", UUID.randomUUID().toString(), "sub_type", "EMPLEADO", "rol", 14,
                "actorType", "AGENT").entrySet()) {
            var claims = claims(); claims.put(entry.getKey(), entry.getValue());
            assertThat(verifier.verify(M1TestTokens.token(claims))).isEmpty();
        }
        for (Instant expiry : new Instant[]{NOW, NOW.minusSeconds(1)}) {
            var claims = claims(); claims.put("exp", Date.from(expiry));
            assertThat(verifier.verify(M1TestTokens.token(claims))).isEmpty();
        }
        var employee = M1TestTokens.claims(M1TestTokens.EMPLOYEE, IdentityActorType.EMPLEADO, NOW);
        employee.put("citizenId", M1TestTokens.EMPLOYEE.toString());
        assertThat(verifier.verify(M1TestTokens.token(employee))).isEmpty();
    }

    @Test
    void rejectsOtherAlgorithmsUnsignedTamperedAndWrongKeyTokens() {
        var claims = claims();
        String otherAlgorithm = Jwts.builder().claims(claims)
                .signWith(new SecretKeySpec(M1TestTokens.bytes(), "HmacSHA512"), Jwts.SIG.HS512).compact();
        String rawBase64Key = M1TestTokens.token(claims, new SecretKeySpec(
                M1TestTokens.encodedKey().getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        String signed = M1TestTokens.token(claims);
        String tampered = signed.substring(0, signed.lastIndexOf('.') + 1) + "invalidsignature";
        for (String token : new String[]{otherAlgorithm, rawBase64Key, tampered,
                Jwts.builder().claims(claims).compact(), "malformed", "", "a".repeat(16385)}) {
            assertThat(verifier.verify(token)).isEmpty();
        }
        assertThat(verifier.verify(null)).isEmpty();
    }

    private static Map<String, Object> claims() {
        return M1TestTokens.claims(M1TestTokens.CITIZEN, IdentityActorType.CIUDADANO, NOW);
    }
}
