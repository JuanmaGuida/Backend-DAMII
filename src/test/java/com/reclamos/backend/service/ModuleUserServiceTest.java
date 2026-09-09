package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleUserServiceTest {
    private static final UUID CITIZEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-08T15:00:00Z");
    private final ModuleUserRepository repository = mock(ModuleUserRepository.class);
    private final ModuleUserService service = new ModuleUserService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void firstLoginCreatesActiveCitizenWithExternalProfile() {
        ExternalIdentityProfile profile = profile("Agente", "de prueba", "agent@example.test", "+541100000000");
        when(repository.findByCitizenId(CITIZEN_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ModuleUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ModuleUser user = service.synchronize(profile);

        assertEquals(CITIZEN_ID, user.getCitizenId());
        assertEquals("Agente", user.getFirstName());
        assertEquals("de prueba", user.getLastName());
        assertEquals("agent@example.test", user.getEmail());
        assertEquals("+541100000000", user.getPhone());
        assertEquals("https://example.test/avatar.png", user.getProfileImageUrl());
        assertEquals(ModuleRole.CITIZEN, user.getRole());
        assertNull(user.getAreaId());
        assertTrue(user.isActive());
        assertEquals(NOW, user.getLastSyncedAt());
        verify(repository).saveAndFlush(user);
    }

    @Test
    void unchangedProfileDoesNotWriteOrRefreshLastSyncedAt() {
        Instant previousSync = Instant.parse("2026-09-01T12:00:00Z");
        ModuleUser existing = existingUser(previousSync);
        ExternalIdentityProfile profile = profile("Agente", "de prueba", "agent@example.test", null);
        when(repository.findByCitizenId(CITIZEN_ID)).thenReturn(Optional.of(existing));

        ModuleUser result = service.synchronize(profile);

        assertSame(existing, result);
        assertEquals(previousSync, result.getLastSyncedAt());
        verify(repository, never()).save(any());
    }

    @Test
    void changedProfileIsUpdatedWithoutOverwritingLocalAuthorization() {
        ModuleUser existing = existingUser(Instant.parse("2026-09-01T12:00:00Z"));
        existing.setRole(ModuleRole.AREA_RESPONSIBLE);
        existing.setAreaId("M6");
        existing.setActive(false);
        existing.setPreferredNotificationChannel("EMAIL");
        ExternalIdentityProfile changed = profile("Nombre nuevo", "Apellido nuevo", "new@example.test", "+54911");
        when(repository.findByCitizenId(CITIZEN_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ModuleUser result = service.synchronize(changed);

        assertEquals("Nombre nuevo", result.getFirstName());
        assertEquals("Apellido nuevo", result.getLastName());
        assertEquals("new@example.test", result.getEmail());
        assertEquals("+54911", result.getPhone());
        assertEquals(ModuleRole.AREA_RESPONSIBLE, result.getRole());
        assertEquals("M6", result.getAreaId());
        assertFalse(result.isActive());
        assertEquals("EMAIL", result.getPreferredNotificationChannel());
        assertEquals(NOW, result.getLastSyncedAt());
        verify(repository).save(existing);
    }

    private static ModuleUser existingUser(Instant lastSyncedAt) {
        ModuleUser user = new ModuleUser();
        user.setCitizenId(CITIZEN_ID);
        user.setFirstName("Agente");
        user.setLastName("de prueba");
        user.setEmail("agent@example.test");
        user.setProfileImageUrl("https://example.test/avatar.png");
        user.setRole(ModuleRole.AGENT);
        user.setActive(true);
        user.setLastSyncedAt(lastSyncedAt);
        return user;
    }

    private static ExternalIdentityProfile profile(String firstName, String lastName, String email, String phone) {
        return new ExternalIdentityProfile("external-subject", CITIZEN_ID, firstName, lastName,
                firstName + " " + lastName, email, phone, "https://example.test/avatar.png");
    }
}
