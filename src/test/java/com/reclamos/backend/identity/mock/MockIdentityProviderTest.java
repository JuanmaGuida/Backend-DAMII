package com.reclamos.backend.identity.mock;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.AuthenticatedSession;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.ModuleRole;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MockIdentityProviderTest {
    private static final Duration TTL = Duration.ofHours(8);
    private static final Instant START = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void authenticatesCitizenWithoutInternalRoles() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        AuthenticatedSession session = authenticate(
                provider,
                MockIdentityProvider.CITIZEN_USERNAME,
                MockIdentityProvider.CITIZEN_PASSWORD
        );

        assertEquals("m1-dev-citizen", session.identity().subjectId());
        assertEquals(UUID.fromString("10000000-0000-0000-0000-000000000001"), session.identity().citizenId());
        assertInstanceOf(UUID.class, session.identity().citizenId());
        assertNull(session.identity().areaId());
        assertTrue(session.identity().roles().isEmpty());
    }

    @Test
    void authenticatesEveryInternalRoleWithExpectedIdentity() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        assertIdentity(provider, MockIdentityProvider.AGENT_USERNAME, MockIdentityProvider.AGENT_PASSWORD,
                "m1-dev-agent", "10000000-0000-0000-0000-000000000002", "M2", ModuleRole.AGENT);
        assertIdentity(provider, MockIdentityProvider.AREA_RESPONSIBLE_USERNAME,
                MockIdentityProvider.AREA_RESPONSIBLE_PASSWORD, "m1-dev-area-responsible",
                "10000000-0000-0000-0000-000000000003", "M6", ModuleRole.AREA_RESPONSIBLE);
        assertIdentity(provider, MockIdentityProvider.SUPERVISOR_USERNAME, MockIdentityProvider.SUPERVISOR_PASSWORD,
                "m1-dev-supervisor", "10000000-0000-0000-0000-000000000004", "M2",
                ModuleRole.SUPERVISOR);
        assertIdentity(provider, MockIdentityProvider.AUDITOR_USERNAME, MockIdentityProvider.AUDITOR_PASSWORD,
                "m1-dev-auditor", "10000000-0000-0000-0000-000000000005", "M2", ModuleRole.AUDITOR);
        assertIdentity(provider, MockIdentityProvider.MODULE_ADMIN_USERNAME,
                MockIdentityProvider.MODULE_ADMIN_PASSWORD, "m1-dev-module-admin",
                "10000000-0000-0000-0000-000000000006", "M2", ModuleRole.MODULE_ADMIN);
    }

    @Test
    void rejectsUnknownUserAndWrongPasswordInTheSameWay() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        Optional<AuthenticatedSession> unknownUser = provider.authenticate("unknown@example.test", "wrong");
        Optional<AuthenticatedSession> wrongPassword = provider.authenticate(
                MockIdentityProvider.AGENT_USERNAME,
                "wrong"
        );

        assertTrue(unknownUser.isEmpty());
        assertEquals(unknownUser, wrongPassword);
        assertTrue(provider.authenticate(null, "wrong").isEmpty());
        assertTrue(provider.authenticate(MockIdentityProvider.AGENT_USERNAME, null).isEmpty());
    }

    @Test
    void createsDistinctSessionsAndResolvesBothTokens() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        AuthenticatedSession first = authenticate(
                provider,
                MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD
        );
        AuthenticatedSession second = authenticate(
                provider,
                MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD
        );

        assertNotEquals(first.token(), second.token());
        assertTrue(first.token().length() >= 40);
        assertEquals(first.identity(), provider.resolve(first.token()).orElseThrow());
        assertEquals(second.identity(), provider.resolve(second.token()).orElseThrow());
        assertEquals(START.plus(TTL), first.expiresAt());
    }

    @Test
    void rejectsUnknownAndBlankTokens() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        assertTrue(provider.resolve("unknown-token").isEmpty());
        assertTrue(provider.resolve(" ").isEmpty());
        assertTrue(provider.resolve(null).isEmpty());
    }

    @Test
    void expiresSessionDeterministicallyWithoutSleeping() {
        MutableClock clock = new MutableClock(START);
        MockIdentityProvider provider = provider(clock);
        AuthenticatedSession session = authenticate(
                provider,
                MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD
        );

        clock.advance(TTL.minusMillis(1));
        assertTrue(provider.resolve(session.token()).isPresent());

        clock.advance(Duration.ofMillis(1));
        assertTrue(provider.resolve(session.token()).isEmpty());
        assertTrue(provider.resolve(session.token()).isEmpty());
    }

    @Test
    void publicIdentityAndSessionContractsNeverContainPasswords() {
        assertFalse(hasRecordComponent(AuthenticatedIdentity.class, "password"));
        assertFalse(hasRecordComponent(AuthenticatedSession.class, "password"));
    }

    @Test
    void mockIsReplaceableThroughIdentityProviderInterface() {
        IdentityProvider provider = provider(new MutableClock(START));

        assertTrue(provider.authenticate(
                MockIdentityProvider.CITIZEN_USERNAME,
                MockIdentityProvider.CITIZEN_PASSWORD
        ).isPresent());
    }

    @Test
    void mockRequiresExplicitDevProfile() {
        Profile profile = MockIdentityProvider.class.getAnnotation(Profile.class);

        assertArrayEquals(new String[]{"dev"}, profile.value());
    }

    @Test
    void rejectsInvalidSessionTtl() {
        MutableClock clock = new MutableClock(START);

        assertThrows(IllegalArgumentException.class,
                () -> new MockIdentityProvider(null, clock, new SecureRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new MockIdentityProvider(Duration.ZERO, clock, new SecureRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new MockIdentityProvider(Duration.ofSeconds(-1), clock, new SecureRandom()));
    }

    private static void assertIdentity(
            MockIdentityProvider provider,
            String username,
            String password,
            String subjectId,
            String citizenId,
            String areaId,
            ModuleRole role
    ) {
        AuthenticatedIdentity identity = authenticate(provider, username, password).identity();
        assertEquals(subjectId, identity.subjectId());
        assertEquals(UUID.fromString(citizenId), identity.citizenId());
        assertEquals(areaId, identity.areaId());
        assertEquals(Set.of(role), identity.roles());
    }

    private static AuthenticatedSession authenticate(
            MockIdentityProvider provider,
            String username,
            String password
    ) {
        return provider.authenticate(username, password).orElseThrow();
    }

    private static MockIdentityProvider provider(Clock clock) {
        return new MockIdentityProvider(TTL, clock, new SecureRandom());
    }

    private static boolean hasRecordComponent(Class<?> type, String componentName) {
        return Arrays.stream(type.getRecordComponents())
                .anyMatch(component -> component.getName().equalsIgnoreCase(componentName));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
