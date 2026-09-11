package com.reclamos.backend.identity.mock;

import com.reclamos.backend.identity.ExternalAuthenticatedSession;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.IdentityProvider;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MockIdentityProviderTest {
    private static final Duration TTL = Duration.ofHours(8);
    private static final Instant START = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void authenticatesCitizenWithExplicitProfileFields() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        ExternalIdentityProfile profile = authenticate(provider, MockIdentityProvider.CITIZEN_USERNAME,
                MockIdentityProvider.CITIZEN_PASSWORD).profile();

        assertEquals("m1-dev-citizen", profile.subjectId());
        assertEquals(UUID.fromString("10000000-0000-0000-0000-000000000001"), profile.citizenId());
        assertInstanceOf(UUID.class, profile.citizenId());
        assertEquals("Ciudadano", profile.firstName());
        assertEquals("de prueba", profile.lastName());
        assertEquals("Ciudadano de prueba", profile.displayName());
        assertEquals(MockIdentityProvider.CITIZEN_USERNAME, profile.email());
    }

    @Test
    void authenticatesEveryFixtureWithoutEmbeddingLocalAuthorization() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        assertProfile(provider, MockIdentityProvider.AGENT_USERNAME, MockIdentityProvider.AGENT_PASSWORD,
                "m1-dev-agent", "10000000-0000-0000-0000-000000000002", "Agente", "de prueba");
        assertProfile(provider, MockIdentityProvider.AREA_RESPONSIBLE_USERNAME,
                MockIdentityProvider.AREA_RESPONSIBLE_PASSWORD, "m1-dev-area-responsible",
                "10000000-0000-0000-0000-000000000003", "Responsable", "de área de prueba");
        assertProfile(provider, MockIdentityProvider.ADMIN_USERNAME, MockIdentityProvider.ADMIN_PASSWORD,
                "m1-dev-module-admin", "10000000-0000-0000-0000-000000000006", "Administrador",
                "de módulo de prueba");

        assertFalse(hasRecordComponent(ExternalIdentityProfile.class, "role"));
        assertFalse(hasRecordComponent(ExternalIdentityProfile.class, "areaId"));
    }

    @Test
    void rejectsUnknownUserAndWrongPasswordInTheSameWay() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        Optional<ExternalAuthenticatedSession> unknownUser = provider.authenticate("unknown@example.test", "wrong");
        Optional<ExternalAuthenticatedSession> wrongPassword = provider.authenticate(
                MockIdentityProvider.AGENT_USERNAME, "wrong");

        assertTrue(unknownUser.isEmpty());
        assertEquals(unknownUser, wrongPassword);
        assertTrue(provider.authenticate(null, "wrong").isEmpty());
        assertTrue(provider.authenticate(MockIdentityProvider.AGENT_USERNAME, null).isEmpty());
    }

    @Test
    void createsDistinctSessionsAndResolvesBothTokens() {
        MockIdentityProvider provider = provider(new MutableClock(START));

        ExternalAuthenticatedSession first = authenticate(provider, MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD);
        ExternalAuthenticatedSession second = authenticate(provider, MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD);

        assertNotEquals(first.token(), second.token());
        assertTrue(first.token().length() >= 40);
        assertEquals(first.profile(), provider.resolve(first.token()).orElseThrow());
        assertEquals(second.profile(), provider.resolve(second.token()).orElseThrow());
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
        ExternalAuthenticatedSession session = authenticate(provider, MockIdentityProvider.AGENT_USERNAME,
                MockIdentityProvider.AGENT_PASSWORD);

        clock.advance(TTL.minusMillis(1));
        assertTrue(provider.resolve(session.token()).isPresent());

        clock.advance(Duration.ofMillis(1));
        assertTrue(provider.resolve(session.token()).isEmpty());
        assertTrue(provider.resolve(session.token()).isEmpty());
    }

    @Test
    void publicExternalContractsNeverContainPasswords() {
        assertFalse(hasRecordComponent(ExternalIdentityProfile.class, "password"));
        assertFalse(hasRecordComponent(ExternalAuthenticatedSession.class, "password"));
    }

    @Test
    void mockIsReplaceableThroughIdentityProviderInterface() {
        IdentityProvider provider = provider(new MutableClock(START));

        assertTrue(provider.authenticate(MockIdentityProvider.CITIZEN_USERNAME,
                MockIdentityProvider.CITIZEN_PASSWORD).isPresent());
    }

    @Test
    void mockRequiresExplicitDevProfile() {
        Profile profile = MockIdentityProvider.class.getAnnotation(Profile.class);

        assertArrayEquals(new String[]{"dev"}, profile.value());
    }

    @Test
    void rejectsInvalidSessionTtl() {
        MutableClock clock = new MutableClock(START);

        assertThrows(IllegalArgumentException.class, () -> new MockIdentityProvider(null, clock, new SecureRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new MockIdentityProvider(Duration.ZERO, clock, new SecureRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new MockIdentityProvider(Duration.ofSeconds(-1), clock, new SecureRandom()));
    }

    private static void assertProfile(MockIdentityProvider provider, String username, String password,
                                      String subjectId, String citizenId, String firstName, String lastName) {
        ExternalIdentityProfile profile = authenticate(provider, username, password).profile();
        assertEquals(subjectId, profile.subjectId());
        assertEquals(UUID.fromString(citizenId), profile.citizenId());
        assertEquals(firstName, profile.firstName());
        assertEquals(lastName, profile.lastName());
        assertEquals(firstName + " " + lastName, profile.displayName());
        assertEquals(username, profile.email());
    }

    private static ExternalAuthenticatedSession authenticate(MockIdentityProvider provider, String username,
                                                              String password) {
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
