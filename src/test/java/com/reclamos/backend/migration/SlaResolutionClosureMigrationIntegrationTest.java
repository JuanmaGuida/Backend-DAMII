package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class SlaResolutionClosureMigrationIntegrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void cleanV1ToV32CreatesTheNormalizedModelAndFinalConstraints() {
        String schema = schema("sla_resolution_clean_");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway flyway = flyway(schema, "32");
            flyway.migrate();

            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("32", installedVersion(database, schema));
            List<String> ticketColumns = columns(database, schema, "tickets");
            assertFalse(ticketColumns.contains("first_response_due_at"));
            assertFalse(ticketColumns.contains("resolution_due_at"));
            assertTrue(columns(database, schema, "ticket_resolutions").contains("resolution_number"));

            List<String> resolutionConstraints = constraints(database, schema, "ticket_resolutions");
            assertTrue(resolutionConstraints.contains("ck_ticket_resolution_number"));
            assertTrue(resolutionConstraints.contains("uk_ticket_resolution_number"));

            UUID ticketId = insertTicket(database, schema, "CLEAN");
            insertResolution(database, schema, ticketId, UUID.randomUUID(), 1,
                    OffsetDateTime.parse("2026-09-12T10:00:00Z"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertResolution(database, schema, ticketId, UUID.randomUUID(), 1,
                            OffsetDateTime.parse("2026-09-12T11:00:00Z")));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertResolution(database, schema, ticketId, UUID.randomUUID(), 0,
                            OffsetDateTime.parse("2026-09-12T12:00:00Z")));

            assertThrows(DataIntegrityViolationException.class, () -> database.update(
                    "UPDATE " + schema + ".tickets SET is_escalated=TRUE, "
                            + "escalation_reason_code='SLA_NEAR_DUE', escalated_at=CURRENT_TIMESTAMP WHERE id=?",
                    ticketId));
            assertDoesNotThrow(() -> database.update(
                    "UPDATE " + schema + ".tickets SET is_escalated=TRUE, "
                            + "escalation_reason_code='SLA_BREACHED', escalated_at=CURRENT_TIMESTAMP WHERE id=?",
                    ticketId));
            assertDoesNotThrow(() -> database.update(
                    "INSERT INTO " + schema + ".ticket_activities "
                            + "(ticket_id, sequence, action_type, actor_type, occurred_at, created_at) "
                            + "VALUES (?, 1, 'SLA_NEAR_DUE', 'SYSTEM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    ticketId));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v30UpgradeBackfillsResolutionNumbersPerTicketDeterministicallyWithoutLosingRows() {
        String schema = schema("sla_resolution_upgrade_");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "30").migrate();
            UUID ticketA = insertTicket(database, schema, "A");
            UUID ticketB = insertTicket(database, schema, "B");
            UUID firstById = UUID.fromString("10000000-0000-0000-0000-000000000001");
            UUID secondById = UUID.fromString("10000000-0000-0000-0000-000000000002");
            UUID earlier = UUID.fromString("10000000-0000-0000-0000-000000000003");
            UUID ticketBResolution = UUID.fromString("20000000-0000-0000-0000-000000000001");
            OffsetDateTime sameTime = OffsetDateTime.parse("2026-09-12T10:00:00Z");

            insertResolutionBeforeV31(database, schema, ticketA, secondById, sameTime);
            insertResolutionBeforeV31(database, schema, ticketA, earlier,
                    OffsetDateTime.parse("2026-09-12T09:00:00Z"));
            insertResolutionBeforeV31(database, schema, ticketA, firstById, sameTime);
            insertResolutionBeforeV31(database, schema, ticketB, ticketBResolution, sameTime);

            Flyway upgraded = flyway(schema, "32");
            upgraded.migrate();

            assertTrue(upgraded.validateWithResult().validationSuccessful);
            assertEquals(4, database.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".ticket_resolutions", Integer.class));
            assertEquals(List.of(1, 2, 3), database.queryForList(
                    "SELECT resolution_number FROM " + schema + ".ticket_resolutions "
                            + "WHERE ticket_id=? ORDER BY resolution_number", Integer.class, ticketA));
            assertEquals(1, database.queryForObject(
                    "SELECT resolution_number FROM " + schema + ".ticket_resolutions WHERE id=?",
                    Integer.class, earlier));
            assertEquals(2, database.queryForObject(
                    "SELECT resolution_number FROM " + schema + ".ticket_resolutions WHERE id=?",
                    Integer.class, firstById));
            assertEquals(3, database.queryForObject(
                    "SELECT resolution_number FROM " + schema + ".ticket_resolutions WHERE id=?",
                    Integer.class, secondById));
            assertEquals(1, database.queryForObject(
                    "SELECT resolution_number FROM " + schema + ".ticket_resolutions WHERE id=?",
                    Integer.class, ticketBResolution));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v32FailsFastInsteadOfReclassifyingLegacyNearDueEscalations() {
        String schema = schema("sla_resolution_legacy_");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "31").migrate();
            UUID ticketId = insertTicket(database, schema, "LEGACY");
            database.update("UPDATE " + schema + ".tickets SET is_escalated=TRUE, "
                            + "escalation_reason_code='SLA_NEAR_DUE', escalated_at=CURRENT_TIMESTAMP WHERE id=?",
                    ticketId);

            Flyway toV32 = flyway(schema, "32");
            FlywayException exception = assertThrows(FlywayException.class, toV32::migrate);
            assertTrue(exception.getMessage().contains(
                    "V32 cannot remove SLA_NEAR_DUE as escalation reason while legacy rows still use it"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private String installedVersion(JdbcTemplate database, String schema) {
        return database.queryForObject("SELECT version FROM " + schema
                + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class);
    }

    private List<String> columns(JdbcTemplate database, String schema, String table) {
        return database.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name=? ORDER BY column_name", String.class, schema, table);
    }

    private List<String> constraints(JdbcTemplate database, String schema, String table) {
        return database.queryForList("SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema=? AND table_name=?", String.class, schema, table);
    }

    private UUID insertTicket(JdbcTemplate database, String schema, String suffix) {
        UUID ticketId = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "is_escalated, status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, NULL, TRUE, id, ticket_type, responsible_area_id, 'Closure', 'Closure test', "
                        + "'{}'::jsonb, 'REGISTERED', 'LOW', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                ticketId, "CL-" + suffix + "-" + ticketId.toString().substring(0, 20),
                "HASH-" + suffix + "-" + ticketId);
        return ticketId;
    }

    private void insertResolutionBeforeV31(JdbcTemplate database, String schema, UUID ticketId,
                                           UUID resolutionId, OffsetDateTime resolvedAt) {
        database.update("INSERT INTO " + schema + ".ticket_resolutions "
                        + "(id, ticket_id, resolution_type, public_message, resolved_by_type, resolved_by_id, "
                        + "resolved_by_module_id, resolved_at) VALUES (?, ?, 'ACTION_COMPLETED', 'Done', "
                        + "'SYSTEM', NULL, 'M2', ?)",
                resolutionId, ticketId, resolvedAt);
    }

    private void insertResolution(JdbcTemplate database, String schema, UUID ticketId,
                                  UUID resolutionId, int resolutionNumber, OffsetDateTime resolvedAt) {
        database.update("INSERT INTO " + schema + ".ticket_resolutions "
                        + "(id, ticket_id, resolution_number, resolution_type, public_message, resolved_by_type, "
                        + "resolved_by_id, resolved_by_module_id, resolved_at) VALUES (?, ?, ?, "
                        + "'ACTION_COMPLETED', 'Done', 'SYSTEM', NULL, 'M2', ?)",
                resolutionId, ticketId, resolutionNumber, resolvedAt);
    }

    private String schema(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
