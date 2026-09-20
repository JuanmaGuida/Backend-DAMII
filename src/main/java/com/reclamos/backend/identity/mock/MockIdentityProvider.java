package com.reclamos.backend.identity.mock;

import com.reclamos.backend.identity.ExternalAuthenticatedSession;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.IdentityProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("dev")
public class MockIdentityProvider implements IdentityProvider {
    static final String CITIZEN_USERNAME = "citizen@example.test";
    static final String AGENT_USERNAME = "agent@example.test";
    static final String AREA_RESPONSIBLE_USERNAME = "area.responsible@example.test";
    static final String ADMIN_USERNAME = "module.admin@example.test";

    private static final int TOKEN_BYTES = 32;

    static final ExternalIdentityProfile CITIZEN_PROFILE = profile(
            "m1-dev-citizen", "10000000-0000-0000-0000-000000000001",
            "Ciudadano", "de prueba", "Ciudadano de prueba", CITIZEN_USERNAME);
    static final ExternalIdentityProfile AGENT_PROFILE = profile(
            "m1-dev-agent", "10000000-0000-0000-0000-000000000002",
            "Agente", "de prueba", "Agente de prueba", AGENT_USERNAME);
    static final ExternalIdentityProfile AREA_RESPONSIBLE_PROFILE = profile(
            "m1-dev-area-responsible", "10000000-0000-0000-0000-000000000003",
            "Responsable", "de área de prueba", "Responsable de área de prueba",
            AREA_RESPONSIBLE_USERNAME);
    static final ExternalIdentityProfile ADMIN_PROFILE = profile(
            "m1-dev-module-admin", "10000000-0000-0000-0000-000000000006",
            "Administrador", "de módulo de prueba", "Administrador de módulo de prueba",
            ADMIN_USERNAME);

    private final Duration sessionTtl;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<String, MockUser> users;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public MockIdentityProvider(
            @Value("${app.identity.mock.session-ttl}") Duration sessionTtl,
            @Value("${app.identity.mock.citizen-password}") String citizenPassword,
            @Value("${app.identity.mock.agent-password}") String agentPassword,
            @Value("${app.identity.mock.area-responsible-password}") String areaResponsiblePassword,
            @Value("${app.identity.mock.admin-password}") String adminPassword
    ) {
        this(sessionTtl, Clock.systemUTC(), new SecureRandom(), citizenPassword, agentPassword,
                areaResponsiblePassword, adminPassword);
    }

    MockIdentityProvider(Duration sessionTtl, Clock clock, SecureRandom secureRandom,
                         String citizenPassword, String agentPassword,
                         String areaResponsiblePassword, String adminPassword) {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
        this.sessionTtl = sessionTtl;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.users = Map.of(
                CITIZEN_USERNAME, user(requirePassword(citizenPassword, "citizen"), CITIZEN_PROFILE),
                AGENT_USERNAME, user(requirePassword(agentPassword, "agent"), AGENT_PROFILE),
                AREA_RESPONSIBLE_USERNAME,
                user(requirePassword(areaResponsiblePassword, "area responsible"), AREA_RESPONSIBLE_PROFILE),
                ADMIN_USERNAME, user(requirePassword(adminPassword, "admin"), ADMIN_PROFILE)
        );
    }

    @Override
    public Optional<ExternalAuthenticatedSession> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }

        MockUser user = users.get(username);
        if (user == null || !passwordMatches(password, user.password())) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        removeExpiredSessions(now);
        Instant expiresAt = now.plus(sessionTtl);
        StoredSession storedSession = new StoredSession(user.identity(), expiresAt);
        String token;
        do {
            token = generateToken();
        } while (sessions.putIfAbsent(token, storedSession) != null);
        return Optional.of(new ExternalAuthenticatedSession(token, expiresAt, user.identity()));
    }

    @Override
    public Optional<ExternalIdentityProfile> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        StoredSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }

        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token, session);
            return Optional.empty();
        }

        return Optional.of(session.identity());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void removeExpiredSessions(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static boolean passwordMatches(String supplied, String expected) {
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ExternalIdentityProfile profile(String subjectId, String citizenId, String firstName,
                                                   String lastName, String displayName, String email) {
        return new ExternalIdentityProfile(subjectId, UUID.fromString(citizenId), firstName, lastName,
                displayName, email, null, null);
    }

    private static MockUser user(String password, ExternalIdentityProfile identity) {
        return new MockUser(password, identity);
    }

    private static String requirePassword(String password, String userLabel) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Mock password for " + userLabel + " must not be blank");
        }
        return password;
    }

    private record MockUser(String password, ExternalIdentityProfile identity) {
        @Override
        public String toString() {
            return "MockUser[password=[REDACTED], identity=[REDACTED]]";
        }
    }

    private record StoredSession(ExternalIdentityProfile identity, Instant expiresAt) {
    }
}
