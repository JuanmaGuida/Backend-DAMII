package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class TicketResolutionActorMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void cleanMigrationChainAllowsNullResolvedByIdForSystemActors() {
        String schema = "resolution_actor_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .cleanDisabled(false)
                    .target(MigrationVersion.fromVersion("27"))
                    .load();

            flyway.migrate();

            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("27", database.queryForObject(
                    "SELECT version FROM " + schema
                            + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class));
            assertEquals("YES", database.queryForObject("""
                    SELECT is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'ticket_resolutions'
                      AND column_name = 'resolved_by_id'
                    """, String.class, schema));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
