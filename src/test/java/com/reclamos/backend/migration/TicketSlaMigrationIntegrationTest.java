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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class TicketSlaMigrationIntegrationTest {
    @Autowired private DataSource dataSource;

    @Test void cleanV1ToV29CreatesAndEnforcesTicketSlaModel() {
        String schema = "ticket_sla_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .cleanDisabled(false)
                    .target(MigrationVersion.fromVersion("29"))
                    .load();
            flyway.migrate();

            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("29", database.queryForObject(
                    "SELECT version FROM " + schema
                            + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class));
            assertEquals(List.of("completed_at", "cycle_number", "due_at", "id", "near_due_at", "paused_at",
                            "policy_id", "sla_type", "started_at", "status", "ticket_id", "total_paused_seconds"),
                    database.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? "
                            + "AND table_name='ticket_sla' ORDER BY column_name", String.class, schema));

            List<String> indexes = database.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE schemaname=? AND tablename='ticket_sla' ORDER BY indexname",
                    String.class, schema);
            assertTrue(indexes.contains("uk_ticket_sla_active_type"));
            assertTrue(indexes.contains("idx_ticket_sla_near_due_candidates"));
            assertTrue(indexes.contains("idx_ticket_sla_breach_candidates"));
            assertTrue(indexes.contains("idx_ticket_sla_history"));
            List<String> constraints = database.queryForList(
                    "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=? "
                            + "AND table_name='ticket_sla'", String.class, schema);
            assertTrue(constraints.contains("fk_ticket_sla_ticket"));
            assertTrue(constraints.contains("fk_ticket_sla_policy"));
            assertTrue(constraints.contains("uk_ticket_sla_cycle"));

            UUID ticketId = insertTicket(database, schema);
            Long policyId = database.queryForObject("SELECT id FROM " + schema
                    + ".sla_policies WHERE priority='LOW' AND sla_type='RESOLUTION'", Long.class);
            insertCycle(database, schema, ticketId, policyId, 1, null, "RUNNING");
            assertDoesNotThrow(() -> database.update("UPDATE " + schema
                    + ".ticket_sla SET status='BREACHED' WHERE ticket_id=?", ticketId));

            assertThrows(DataIntegrityViolationException.class,
                    () -> insertCycle(database, schema, ticketId, policyId, 2, null, "NEAR_DUE"));
            database.update("UPDATE " + schema
                            + ".ticket_sla SET completed_at=?, status='BREACHED' WHERE ticket_id=?",
                    OffsetDateTime.parse("2026-09-11T22:30:00Z"), ticketId);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertCycle(database, schema, ticketId, policyId, 1,
                            OffsetDateTime.parse("2026-09-11T20:00:00Z"), "MET"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> database.update("UPDATE " + schema
                            + ".ticket_sla SET total_paused_seconds=-1 WHERE ticket_id=?", ticketId));
            assertThrows(DataIntegrityViolationException.class,
                    () -> database.update("UPDATE " + schema
                            + ".ticket_sla SET near_due_at=started_at - INTERVAL '1 second' WHERE ticket_id=?", ticketId));
            assertDoesNotThrow(() -> database.update("INSERT INTO " + schema
                            + ".ticket_activities (ticket_id, sequence, action_type, actor_type, occurred_at, created_at) "
                            + "VALUES (?, 1, 'SLA_NEAR_DUE', 'SYSTEM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                            + "(?, 2, 'SLA_BREACHED', 'SYSTEM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    ticketId, ticketId));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private UUID insertTicket(JdbcTemplate database, String schema) {
        UUID ticketId = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "is_escalated, status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, NULL, TRUE, id, ticket_type, responsible_area_id, 'SLA', 'SLA test', "
                        + "'{}'::jsonb, 'REGISTERED', 'LOW', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                ticketId, "SLA-V29-" + ticketId, "HASH-V29-" + ticketId);
        return ticketId;
    }

    private void insertCycle(JdbcTemplate database, String schema, UUID ticketId, Long policyId,
                             int cycleNumber, OffsetDateTime completedAt, String status) {
        database.update("INSERT INTO " + schema + ".ticket_sla "
                        + "(ticket_id, sla_type, cycle_number, policy_id, started_at, near_due_at, due_at, "
                        + "completed_at, status, total_paused_seconds) VALUES (?, 'RESOLUTION', ?, ?, ?, ?, ?, ?, ?, 0)",
                ticketId, cycleNumber, policyId,
                OffsetDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 11, 18, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 11, 22, 0, 0, 0, ZoneOffset.UTC), completedAt, status);
    }
}
