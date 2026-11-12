package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ExternalAuthenticatedSession;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.exception.InvalidAuthenticationException;
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
        user.setActorId(user.getCitizenId());
        user.setActorType(com.reclamos.backend.identity.IdentityActorType.CIUDADANO);
        user.setIdentitySource(com.reclamos.backend.identity.IdentitySource.MOCK);
        user.setFirstName(PROFILE.firstName());
        user.setLastName(PROFILE.lastName());
        user.setRole(ModuleRole.AREA_RESPONSIBLE);
        user.setAreaId("M6");
        user.setActive(active);
        return user;
    }

    @Test
    void unprovisionedEmployeeIsRejectedForLoginAndBearerWithoutFallback() {
        var employee = new ExternalIdentityProfile("14", CITIZEN_ID, IdentityActorType.EMPLEADO,
                IdentitySource.M1, null, null, null, null, null, null, null);
        when(identityProvider.authenticate("employee@example.test", "synthetic"))
                .thenReturn(Optional.of(new ExternalAuthenticatedSession("employee-token", Instant.now(), employee)));
        when(identityProvider.resolve("employee-token")).thenReturn(Optional.of(employee));
        when(moduleUserService.synchronize(employee)).thenThrow(new InvalidAuthenticationException());

        assertTrue(authService.authenticate("employee@example.test", "synthetic").isEmpty());
        assertTrue(authService.resolve("employee-token").isEmpty());
    }

    @Test
    void employeeAndPartialProfileUseMergedLocalNameAndLocalStaffRole() {
        var employee = new ExternalIdentityProfile("14", CITIZEN_ID, IdentityActorType.EMPLEADO,
                IdentitySource.M1, null, null, null, null, null, null, null);
        ModuleUser local = localUser(true);
        local.setCitizenId(null);
        local.setActorType(IdentityActorType.EMPLEADO);
        local.setIdentitySource(IdentitySource.M1);
        when(identityProvider.resolve("employee-token")).thenReturn(Optional.of(employee));
        when(moduleUserService.synchronize(employee)).thenReturn(local);

        var result = authService.resolve("employee-token").orElseThrow();
        assertEquals(CITIZEN_ID, result.actorId());
        assertNull(result.citizenId());
        assertEquals("Responsable de área", result.displayName());
        assertEquals(ModuleRole.AREA_RESPONSIBLE, result.role());
    }

    private static void assertEffectiveAuthorization(AuthenticatedIdentity identity) {
        assertEquals(PROFILE.subjectId(), identity.subjectId());
        assertEquals(CITIZEN_ID, identity.citizenId());
        assertEquals(PROFILE.displayName(), identity.displayName());
        assertEquals(ModuleRole.AREA_RESPONSIBLE, identity.role());
        assertEquals("M6", identity.areaId());
    }
}
