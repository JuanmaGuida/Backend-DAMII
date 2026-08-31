package com.reclamos.backend.identity.mock;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.AuthenticatedSession;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.ModuleRole;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("dev")
public class MockIdentityProvider implements IdentityProvider {
    static final String CITIZEN_USERNAME = "citizen@example.test";
    static final String CITIZEN_PASSWORD = "CitizenDev!2026";
    static final String AGENT_USERNAME = "agent@example.test";
    static final String AGENT_PASSWORD = "AgentDev!2026";
    static final String AREA_RESPONSIBLE_USERNAME = "area.responsible@example.test";
    static final String AREA_RESPONSIBLE_PASSWORD = "AreaDev!2026";
    static final String SUPERVISOR_USERNAME = "supervisor@example.test";
    static final String SUPERVISOR_PASSWORD = "SupervisorDev!2026";
    static final String AUDITOR_USERNAME = "auditor@example.test";
    static final String AUDITOR_PASSWORD = "AuditorDev!2026";
    static final String MODULE_ADMIN_USERNAME = "module.admin@example.test";
    static final String MODULE_ADMIN_PASSWORD = "AdminDev!2026";

    private static final int TOKEN_BYTES = 32;

    private static final Map<String, MockUser> USERS = Map.of(
            CITIZEN_USERNAME, user(
                    CITIZEN_PASSWORD,
                    "m1-dev-citizen",
                    "10000000-0000-0000-0000-000000000001",
                    "Ciudadano de prueba",
                    null,
                    Set.of()
            ),
            AGENT_USERNAME, user(
                    AGENT_PASSWORD,
                    "m1-dev-agent",
                    "10000000-0000-0000-0000-000000000002",
                    "Agente de prueba",
                    "M2",
                    Set.of(ModuleRole.AGENT)
            ),
            AREA_RESPONSIBLE_USERNAME, user(
                    AREA_RESPONSIBLE_PASSWORD,
                    "m1-dev-area-responsible",
                    "10000000-0000-0000-0000-000000000003",
                    "Responsable de área de prueba",
                    "M6",
                    Set.of(ModuleRole.AREA_RESPONSIBLE)
            ),
            SUPERVISOR_USERNAME, user(
                    SUPERVISOR_PASSWORD,
                    "m1-dev-supervisor",
                    "10000000-0000-0000-0000-000000000004",
                    "Supervisor de prueba",
                    "M2",
                    Set.of(ModuleRole.SUPERVISOR)
            ),
            AUDITOR_USERNAME, user(
                    AUDITOR_PASSWORD,
                    "m1-dev-auditor",
                    "10000000-0000-0000-0000-000000000005",
                    "Auditor de prueba",
                    "M2",
                    Set.of(ModuleRole.AUDITOR)
            ),
            MODULE_ADMIN_USERNAME, user(
                    MODULE_ADMIN_PASSWORD,
                    "m1-dev-module-admin",
                    "10000000-0000-0000-0000-000000000006",
                    "Administrador de módulo de prueba",
                    "M2",
                    Set.of(ModuleRole.MODULE_ADMIN)
            )
    );

    private final Duration sessionTtl;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public MockIdentityProvider(@Value("${app.identity.mock.session-ttl}") Duration sessionTtl) {
        this(sessionTtl, Clock.systemUTC(), new SecureRandom());
    }

    MockIdentityProvider(Duration sessionTtl, Clock clock, SecureRandom secureRandom) {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
        this.sessionTtl = sessionTtl;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    @Override
    public Optional<AuthenticatedSession> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }

        MockUser user = USERS.get(username);
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
        return Optional.of(new AuthenticatedSession(token, expiresAt, user.identity()));
    }

    @Override
    public Optional<AuthenticatedIdentity> resolve(String token) {
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

    private static MockUser user(
            String password,
            String subjectId,
            String citizenId,
            String displayName,
            String areaId,
            Set<ModuleRole> roles
    ) {
        return new MockUser(
                password,
                new AuthenticatedIdentity(subjectId, UUID.fromString(citizenId), displayName, areaId, roles)
        );
    }

    private record MockUser(String password, AuthenticatedIdentity identity) {
        @Override
        public String toString() {
            return "MockUser[password=[REDACTED], identity=[REDACTED]]";
        }
    }

    private record StoredSession(AuthenticatedIdentity identity, Instant expiresAt) {
    }
}
