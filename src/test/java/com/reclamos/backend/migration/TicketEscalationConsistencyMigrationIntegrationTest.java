package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class TicketEscalationConsistencyMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void cleanMigrationChainEnforcesConsistentEscalationFields() {
        String schema = "ticket_escalation_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .cleanDisabled(false)
                    .target(MigrationVersion.fromVersion("28"))
                    .load();

            flyway.migrate();

            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("28", database.queryForObject(
                    "SELECT version FROM " + schema
                            + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class));
            assertDoesNotThrow(() -> insertTicket(database, schema, false, null, null, "NOT_ESCALATED"));
            assertDoesNotThrow(() -> insertTicket(database, schema, true,
                    "CRITICAL_PRIORITY", Instant.parse("2026-09-11T12:00:00Z"), "ESCALATED"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertTicket(database, schema, false,
                            "CRITICAL_PRIORITY", null, "FALSE_WITH_REASON"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertTicket(database, schema, true,
                            null, Instant.parse("2026-09-11T12:00:00Z"), "TRUE_WITHOUT_REASON"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void insertTicket(JdbcTemplate database, String schema, boolean escalated,
                              String reasonCode, Instant escalatedAt, String suffix) {
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "is_escalated, escalation_reason_code, escalated_at, status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, NULL, TRUE, id, ticket_type, responsible_area_id, 'Resumen', 'Descripción', "
                        + "'{}'::jsonb, 'REGISTERED', 'LOW', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                UUID.randomUUID(), "ESC-V28-" + suffix, "HASH-V28-" + suffix,
                escalated, reasonCode, escalatedAt == null ? null
                        : OffsetDateTime.ofInstant(escalatedAt, ZoneOffset.UTC));
    }
}
