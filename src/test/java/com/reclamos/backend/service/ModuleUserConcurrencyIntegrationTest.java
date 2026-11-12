package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("dev")
class ModuleUserConcurrencyIntegrationTest {
    private static final UUID CITIZEN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final ExternalIdentityProfile PROFILE = new ExternalIdentityProfile(
            "concurrent-subject", CITIZEN_ID, "Nombre", "Concurrente",
            "Nombre Concurrente", "concurrent@example.test", "+541155555555", null);

    @Autowired
    private ModuleUserRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    void concurrentFirstSynchronizationReturnsTheSinglePersistedModuleUserToBothCallers() throws Exception {
        repository.findByActorId(CITIZEN_ID).ifPresent(repository::delete);
        CyclicBarrier initialReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        ModuleUserRepository coordinatedRepository = mock(
                ModuleUserRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            if (reads.getAndIncrement() < 2) {
                initialReads.await(10, TimeUnit.SECONDS);
                return Optional.empty();
            }
            return repository.findByActorId(CITIZEN_ID);
        }).when(coordinatedRepository).findByActorId(CITIZEN_ID);
        ModuleUserService service = new ModuleUserService(coordinatedRepository, clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ModuleUser> first = executor.submit(() -> service.synchronize(PROFILE));
            Future<ModuleUser> second = executor.submit(() -> service.synchronize(PROFILE));

            ModuleUser firstResult = first.get(15, TimeUnit.SECONDS);
            ModuleUser secondResult = second.get(15, TimeUnit.SECONDS);

            assertNotNull(firstResult.getId());
            assertEquals(firstResult.getId(), secondResult.getId());
            assertEquals(CITIZEN_ID, firstResult.getCitizenId());
            assertEquals(CITIZEN_ID, secondResult.getCitizenId());
            assertEquals(ModuleRole.CITIZEN, firstResult.getRole());
            assertEquals("Nombre", firstResult.getFirstName());
            assertEquals("Concurrente", firstResult.getLastName());
            assertEquals("concurrent@example.test", firstResult.getEmail());
            assertEquals("+541155555555", firstResult.getPhone());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM module_users WHERE citizen_id=?", Integer.class, CITIZEN_ID));
            assertEquals(firstResult.getId(), repository.findByActorId(CITIZEN_ID).orElseThrow().getId());
        } finally {
            executor.shutdownNow();
            repository.findByActorId(CITIZEN_ID).ifPresent(repository::delete);
        }
    }

    @Test
    void concurrentPartialUpdatesPreserveBothProfileChangesAndLocalAuthorization() throws Exception {
        UUID id = UUID.randomUUID();
        ModuleUserService initial = new ModuleUserService(repository, clock);
        ModuleUser user = initial.synchronize(new ExternalIdentityProfile("same-sub", id,
                "Original", "Name", null, "old@example.test", null, null));
        user.setRole(ModuleRole.AREA_RESPONSIBLE);
        user.setAreaId("M6");
        user.setActive(false);
        user.setPreferredNotificationChannel("EMAIL");
        repository.saveAndFlush(user);
        CyclicBarrier initialReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        ModuleUserRepository coordinated = mock(ModuleUserRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            Optional<ModuleUser> result = repository.findByActorId(id);
            if (reads.getAndIncrement() < 2) initialReads.await(10, TimeUnit.SECONDS);
            return result;
        }).when(coordinated).findByActorId(id);
        ModuleUserService service = new ModuleUserService(coordinated, clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ModuleUser> first = executor.submit(() -> service.synchronize(new ExternalIdentityProfile(
                    "same-sub", id, "Updated", null, null, null, null, null)));
            Future<ModuleUser> second = executor.submit(() -> service.synchronize(new ExternalIdentityProfile(
                    "same-sub", id, null, null, null, "new@example.test", null, null)));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            ModuleUser result = repository.findByActorId(id).orElseThrow();
            assertEquals("Updated", result.getFirstName());
            assertEquals("Name", result.getLastName());
            assertEquals("new@example.test", result.getEmail());
            assertEquals(user.getId(), result.getId());
            assertEquals(ModuleRole.AREA_RESPONSIBLE, result.getRole());
            assertEquals("M6", result.getAreaId());
            assertEquals("EMAIL", result.getPreferredNotificationChannel());
            org.junit.jupiter.api.Assertions.assertFalse(result.isActive());
        } finally {
            executor.shutdownNow();
            repository.findByActorId(id).ifPresent(repository::delete);
        }
    }
}
