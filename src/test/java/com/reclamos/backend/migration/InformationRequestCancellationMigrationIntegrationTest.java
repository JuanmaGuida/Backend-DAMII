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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class InformationRequestCancellationMigrationIntegrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void cleanV1ToV33AllowsOnlyTheFourInformationRequestStatuses() {
        String schema = schema("information_request_clean_");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway flyway = flyway(schema, "33");
            flyway.migrate();

            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("33", installedVersion(database, schema));
            String definition = database.queryForObject("""
                    SELECT pg_get_constraintdef(constraint_definition.oid)
                    FROM pg_constraint constraint_definition
                    JOIN pg_class table_definition ON table_definition.oid = constraint_definition.conrelid
                    JOIN pg_namespace namespace_definition ON namespace_definition.oid = table_definition.relnamespace
                    WHERE namespace_definition.nspname = ?
                      AND table_definition.relname = 'information_requests'
                      AND constraint_definition.conname = 'ck_information_request_status'
                    """, String.class, schema);
            assertTrue(definition.contains("'PENDING'"));
            assertTrue(definition.contains("'ANSWERED'"));
            assertTrue(definition.contains("'EXPIRED'"));
            assertTrue(definition.contains("'CANCELLED'"));

            UUID ticketId = insertTicket(database, schema, "CLEAN");
            assertDoesNotThrow(() -> insertInformationRequest(
                    database, schema, ticketId, InformationRequestId.CANCELLED, "CANCELLED"));
            assertThrows(DataIntegrityViolationException.class, () -> insertInformationRequest(
                    database, schema, ticketId, InformationRequestId.INVALID, "INVALID"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v32UpgradePreservesPendingRowsAndLetsThemBecomeCancelled() {
        String schema = schema("information_request_upgrade_");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "32").migrate();
            UUID ticketId = insertTicket(database, schema, "UPGRADE");
            insertInformationRequest(database, schema, ticketId, InformationRequestId.PENDING, "PENDING");

            Flyway upgraded = flyway(schema, "33");
            upgraded.migrate();

            assertTrue(upgraded.validateWithResult().validationSuccessful);
            assertEquals("PENDING", database.queryForObject(
                    "SELECT status FROM " + schema + ".information_requests WHERE id=?",
                    String.class, InformationRequestId.PENDING.id));
            assertDoesNotThrow(() -> database.update(
                    "UPDATE " + schema + ".information_requests SET status='CANCELLED' WHERE id=?",
                    InformationRequestId.PENDING.id));
            assertEquals(0, database.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".information_requests WHERE status='PENDING' AND due_at<=CURRENT_TIMESTAMP",
                    Integer.class));
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

    private UUID insertTicket(JdbcTemplate database, String schema, String suffix) {
        UUID ticketId = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "is_escalated, status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, NULL, TRUE, id, ticket_type, responsible_area_id, 'Information request', "
                        + "'Migration test', '{}'::jsonb, 'REGISTERED', 'LOW', FALSE, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM " + schema
                        + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                ticketId, "IR-" + suffix + "-" + ticketId.toString().substring(0, 20),
                "HASH-" + suffix + "-" + ticketId);
        return ticketId;
    }

    private void insertInformationRequest(JdbcTemplate database, String schema, UUID ticketId,
                                          InformationRequestId request, String status) {
        OffsetDateTime requestedAt = OffsetDateTime.parse("2026-09-12T10:00:00Z");
        database.update("INSERT INTO " + schema + ".information_requests "
                        + "(id, ticket_id, requested_by_module_id, requested_by_actor_type, requested_by_actor_id, "
                        + "message_for_citizen, resume_status, status, requested_at, due_at) "
                        + "VALUES (?, ?, 'M6', 'AREA_RESPONSIBLE', 'M6-actor', 'Dato requerido', "
                        + "'ROUTED', ?, ?, ?)",
                request.id, ticketId, status, requestedAt, requestedAt.plusHours(1));
    }

    private String schema(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private enum InformationRequestId {
        PENDING(UUID.fromString("30000000-0000-0000-0000-000000000001")),
        CANCELLED(UUID.fromString("30000000-0000-0000-0000-000000000002")),
        INVALID(UUID.fromString("30000000-0000-0000-0000-000000000003"));

        private final UUID id;

        InformationRequestId(UUID id) {
            this.id = id;
        }
    }
}
