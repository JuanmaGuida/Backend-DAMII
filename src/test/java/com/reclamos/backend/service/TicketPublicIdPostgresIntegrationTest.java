package com.reclamos.backend.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class TicketPublicIdPostgresIntegrationTest {
    private static final int CONCURRENT_GENERATIONS = 24;
    private static final Instant CREATION_INSTANT = Instant.parse("2026-09-11T15:00:00Z");

    @Autowired
    private TicketPublicIdGenerator generator;

    @Autowired
    private JdbcTemplate database;

    @Autowired
    private DataSource dataSource;

    @Test
    void concurrentGenerationUsesTheSinglePostgresSequenceWithoutCollisions() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_GENERATIONS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_GENERATIONS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = IntStream.range(0, CONCURRENT_GENERATIONS)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return generator.generate(CREATION_INSTANT);
                    }))
                    .toList();

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Set<String> publicIds = new HashSet<>();
            for (Future<String> future : futures) {
                publicIds.add(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(CONCURRENT_GENERATIONS, publicIds.size());
            assertTrue(publicIds.stream().allMatch(value -> value.matches("^TK-2026-[0-9]{6,}$")));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cleanMigrationChainCreatesNonCyclingGlobalSequenceAndPreservesPublicIdUniqueConstraint() {
        String schema = "public_id_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .cleanDisabled(false)
                    .target(MigrationVersion.fromVersion("26"))
                    .load();

            flyway.migrate();
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals(1, database.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.sequences
                    WHERE sequence_schema = ? AND sequence_name = 'ticket_public_id_seq'
                    """, Integer.class, schema));
            assertEquals("NO", database.queryForObject("""
                    SELECT cycle_option
                    FROM information_schema.sequences
                    WHERE sequence_schema = ? AND sequence_name = 'ticket_public_id_seq'
                    """, String.class, schema));
            assertEquals(1, database.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_constraint constraint_definition
                    JOIN pg_class relation ON relation.oid = constraint_definition.conrelid
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = ?
                      AND relation.relname = 'tickets'
                      AND constraint_definition.conname = 'uk_ticket_public_id'
                      AND constraint_definition.contype = 'u'
                    """, Integer.class, schema));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
