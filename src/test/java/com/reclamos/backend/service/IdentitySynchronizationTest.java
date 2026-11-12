package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.exception.InvalidAuthenticationException;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentitySynchronizationTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private final ModuleUserRepository repository = mock(ModuleUserRepository.class);
    private final ModuleUserService service = new ModuleUserService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void absentAndBlankProfileFieldsNeverEraseExistingData() {
        ModuleUser user = citizen();
        when(repository.findByActorId(ID)).thenReturn(Optional.of(user));
        var partial = new ExternalIdentityProfile("17", ID, null, "", null, null, " ", null);

        assertSame(user, service.synchronize(partial));
        assertEquals("Old", user.getFirstName());
        assertEquals("old@example.test", user.getEmail());
        verify(repository, never()).updateExternalProfile(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void partialChangeDoesNotWriteAuthorizationOrMissingFields() {
        ModuleUser user = citizen();
        user.setActive(false);
        user.setAreaId("M6");
        user.setRole(ModuleRole.AREA_RESPONSIBLE);
        user.setPreferredNotificationChannel("EMAIL");
        when(repository.findByActorId(ID)).thenReturn(Optional.of(user));

        ModuleUser result = service.synchronize(new ExternalIdentityProfile("17", ID, "New", null,
                null, null, null, null));

        assertSame(user, result);
        assertFalse(result.isActive());
        assertEquals(ModuleRole.AREA_RESPONSIBLE, result.getRole());
        assertEquals("M6", result.getAreaId());
        assertEquals("EMAIL", result.getPreferredNotificationChannel());
        verify(repository).updateExternalProfile(ID, IdentitySource.MOCK, "New", null, null, null, null, NOW);
        verify(repository, never()).save(any());
    }

    @Test
    void employeeWithoutLocalStaffProvisioningCannotAcquireCitizenOrStaffRole() {
        assertThrows(InvalidAuthenticationException.class, () -> service.synchronize(employee()));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void employeeUsesItsProvisionedActorWithoutCitizenIdentity() {
        ModuleUser user = citizen();
        user.setActorType(IdentityActorType.EMPLEADO);
        user.setIdentitySource(IdentitySource.M1);
        user.setCitizenId(null);
        when(repository.findByActorId(ID)).thenReturn(Optional.of(user));

        assertSame(user, service.synchronize(employee()));
        assertNull(user.getCitizenId());
        assertEquals(ModuleRole.AGENT, user.getRole());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void realProviderCannotTakeOverMockActorEvenWithSameUuid() {
        when(repository.findByActorId(ID)).thenReturn(Optional.of(citizen()));
        var real = new ExternalIdentityProfile("17", ID, IdentityActorType.CIUDADANO, IdentitySource.M1,
                ID, "New", "Name", null, null, null, null);
        assertThrows(InvalidAuthenticationException.class, () -> service.synchronize(real));
        verify(repository, never()).updateExternalProfile(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void identityTypeCannotBeRebound() {
        when(repository.findByActorId(ID)).thenReturn(Optional.of(citizen()));
        assertThrows(InvalidAuthenticationException.class, () -> service.synchronize(employee()));
    }

    @Test
    void incompleteFirstCitizenProfileIsRejectedWithoutInventingNames() {
        assertThrows(InvalidAuthenticationException.class,
                () -> service.synchronize(new ExternalIdentityProfile("17", ID, null, "Name", null, null, null, null)));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void constraintConflictDoesNotMergeUsersByCitizenFallbackOrContact() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("conflict"));
        assertThrows(InvalidAuthenticationException.class, () -> service.synchronize(
                new ExternalIdentityProfile("17", ID, "Old", "Name", null, "old@example.test", null, null)));
        verify(repository, never()).findByCitizenId(any());
    }

    @Test
    void modelRejectsFakeCitizenIdsAndCitizenRoleForEmployees() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentityProfile("17", ID,
                IdentityActorType.EMPLEADO, IdentitySource.M1, ID, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentityProfile("17", ID,
                IdentityActorType.CIUDADANO, IdentitySource.M1, UUID.randomUUID(), null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedIdentity("17", ID,
                IdentityActorType.EMPLEADO, null, "Employee", null, ModuleRole.CITIZEN));
    }

    private static ModuleUser citizen() {
        ModuleUser user = new ModuleUser();
        user.setActorId(ID);
        user.setCitizenId(ID);
        user.setActorType(IdentityActorType.CIUDADANO);
        user.setIdentitySource(IdentitySource.MOCK);
        user.setFirstName("Old");
        user.setLastName("Name");
        user.setEmail("old@example.test");
        user.setRole(ModuleRole.AGENT);
        user.setLastSyncedAt(NOW.minusSeconds(600));
        return user;
    }

    private static ExternalIdentityProfile employee() {
        return new ExternalIdentityProfile("17", ID, IdentityActorType.EMPLEADO, IdentitySource.M1,
                null, "Old", "Name", null, null, null, null);
    }
}
