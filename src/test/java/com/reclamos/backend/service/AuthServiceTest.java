package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ExternalAuthenticatedSession;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.ModuleRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private static final UUID CITIZEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final ExternalIdentityProfile PROFILE = new ExternalIdentityProfile(
            "external-area-responsible", CITIZEN_ID, "Responsable", "de área",
            "Responsable de área", "area@example.test", null, null);
    private final IdentityProvider identityProvider = mock(IdentityProvider.class);
    private final ModuleUserService moduleUserService = mock(ModuleUserService.class);
    private final AuthService authService = new AuthService(identityProvider, moduleUserService);

    @Test
    void loginBuildsEffectiveSessionFromLocalRoleAndArea() {
        Instant expiresAt = Instant.parse("2026-09-08T23:00:00Z");
        when(identityProvider.authenticate("area@example.test", "secret"))
                .thenReturn(Optional.of(new ExternalAuthenticatedSession("token", expiresAt, PROFILE)));
        when(moduleUserService.synchronize(PROFILE)).thenReturn(localUser(true));

        var session = authService.authenticate("area@example.test", "secret").orElseThrow();

        assertEquals("token", session.token());
        assertEquals(expiresAt, session.expiresAt());
        assertEffectiveAuthorization(session.identity());
    }

    @Test
    void bearerResolutionUsesTheSameCentralizedLocalAuthorization() {
        when(identityProvider.resolve("token")).thenReturn(Optional.of(PROFILE));
        when(moduleUserService.synchronize(PROFILE)).thenReturn(localUser(true));

        AuthenticatedIdentity identity = authService.resolve("token").orElseThrow();

        assertEffectiveAuthorization(identity);
    }

    @Test
    void inactiveLocalUserIsRejectedForLoginAndBearerResolution() {
        when(identityProvider.authenticate("area@example.test", "secret"))
                .thenReturn(Optional.of(new ExternalAuthenticatedSession(
                        "token", Instant.parse("2026-09-08T23:00:00Z"), PROFILE)));
        when(identityProvider.resolve("token")).thenReturn(Optional.of(PROFILE));
        when(moduleUserService.synchronize(PROFILE)).thenReturn(localUser(false));

        assertTrue(authService.authenticate("area@example.test", "secret").isEmpty());
        assertTrue(authService.resolve("token").isEmpty());
    }

    private static ModuleUser localUser(boolean active) {
        ModuleUser user = new ModuleUser();
        user.setCitizenId(CITIZEN_ID);
        user.setFirstName(PROFILE.firstName());
        user.setLastName(PROFILE.lastName());
        user.setRole(ModuleRole.AREA_RESPONSIBLE);
        user.setAreaId("M6");
        user.setActive(active);
        return user;
    }

    private static void assertEffectiveAuthorization(AuthenticatedIdentity identity) {
        assertEquals(PROFILE.subjectId(), identity.subjectId());
        assertEquals(CITIZEN_ID, identity.citizenId());
        assertEquals(PROFILE.displayName(), identity.displayName());
        assertEquals(ModuleRole.AREA_RESPONSIBLE, identity.role());
        assertEquals("M6", identity.areaId());
    }
}
