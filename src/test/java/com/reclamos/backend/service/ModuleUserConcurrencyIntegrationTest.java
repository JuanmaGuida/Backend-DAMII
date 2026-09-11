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
        repository.findByCitizenId(CITIZEN_ID).ifPresent(repository::delete);
        CyclicBarrier initialReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        ModuleUserRepository coordinatedRepository = mock(
                ModuleUserRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            if (reads.getAndIncrement() < 2) {
                initialReads.await(10, TimeUnit.SECONDS);
                return Optional.empty();
            }
            return repository.findByCitizenId(CITIZEN_ID);
        }).when(coordinatedRepository).findByCitizenId(CITIZEN_ID);
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
            assertEquals(firstResult.getId(), repository.findByCitizenId(CITIZEN_ID).orElseThrow().getId());
        } finally {
            executor.shutdownNow();
            repository.findByCitizenId(CITIZEN_ID).ifPresent(repository::delete);
        }
    }
}
