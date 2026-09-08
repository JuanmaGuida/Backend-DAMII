package com.reclamos.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class ActorSemanticsMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cleanV1ToV12HasTheApprovedActorConstraints() {
        assertEquals("12", jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class));

        String activity = constraintDefinition(jdbcTemplate, "public", "ck_ticket_activity_actor_type");
        String message = constraintDefinition(jdbcTemplate, "public", "ck_ticket_message_author_type");
        String cancellation = constraintDefinition(jdbcTemplate, "public", "ck_ticket_cancellation_actor");
        for (String actor : new String[]{"CITIZEN", "AGENT", "AREA_RESPONSIBLE", "ADMIN", "EXTERNAL_USER", "SYSTEM"}) {
            assertTrue(activity.contains(actor));
            assertTrue(message.contains(actor));
        }
        for (String actor : new String[]{"CITIZEN", "AGENT", "AREA_RESPONSIBLE", "ADMIN", "EXTERNAL_USER", "SYSTEM"}) {
            assertTrue(cancellation.contains(actor));
        }
    }

    @Test
    void v10AndV11ConvertOnlyUnambiguousHistoryAndEnforceFinalActorTypes() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "9").migrate();
            UUID citizenId = UUID.randomUUID();
            UUID identifiedTicket = insertTicket(database, schema, citizenId, "IDENTIFIED");
            UUID timeoutTicket = insertTicket(database, schema, UUID.randomUUID(), "TIMEOUT");
            UUID unrelatedTicket = insertTicket(database, schema, UUID.randomUUID(), "UNRELATED");
            UUID anonymousTicket = insertTicket(database, schema, null, "ANONYMOUS");

            insertActivity(database, schema, identifiedTicket, 1, "TICKET_CREATED", "CITIZEN",
                    "old-subject", null, null);
            insertAnsweredRequest(database, schema, identifiedTicket, "old-subject");
            insertActivity(database, schema, identifiedTicket, 2, "INFORMATION_PROVIDED", "CITIZEN",
                    "old-subject", "M2", null);

            database.update("INSERT INTO " + schema + ".ticket_cancellations "
                            + "(id, ticket_id, reason_code, cancelled_by_type, cancelled_by_id, "
                            + "cancelled_by_module_id, cancelled_at) "
                            + "VALUES (?, ?, 'INFO_TIMEOUT', 'ADMIN', 'system', 'M2', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), timeoutTicket);
            insertActivity(database, schema, timeoutTicket, 1, "CANCELLED", "ADMIN", "system", "M2",
                    "INFO_TIMEOUT");
            insertActivity(database, schema, unrelatedTicket, 1, "REVIEW_STARTED", "ADMIN", "system", "M2", null);

            insertAnsweredRequest(database, schema, anonymousTicket, "tracking-actor");
            insertActivity(database, schema, anonymousTicket, 1, "INFORMATION_PROVIDED", "CITIZEN",
                    "tracking-actor", "M2", null);

            flyway(schema, null).migrate();

            assertEquals(citizenId.toString(), activityActorId(database, schema, identifiedTicket, 1));
            assertEquals(citizenId.toString(), scalar(database, "SELECT answered_by_id FROM " + schema
                    + ".information_requests WHERE ticket_id='" + identifiedTicket + "'"));
            assertEquals(citizenId.toString(), activityActorId(database, schema, identifiedTicket, 2));
            assertEquals("SYSTEM|<null>|M2", scalar(database, "SELECT cancelled_by_type || '|' || "
                    + "COALESCE(cancelled_by_id, '<null>') || '|' || cancelled_by_module_id FROM " + schema
                    + ".ticket_cancellations WHERE ticket_id='" + timeoutTicket + "'"));
            assertEquals("SYSTEM|<null>|M2", activityActor(database, schema, timeoutTicket, 1));
            assertEquals("ADMIN|system|M2", activityActor(database, schema, unrelatedTicket, 1));
            assertEquals("tracking-actor", scalar(database, "SELECT answered_by_id FROM " + schema
                    + ".information_requests WHERE ticket_id='" + anonymousTicket + "'"));
            assertEquals("tracking-actor", activityActorId(database, schema, anonymousTicket, 1));

            insertActivity(database, schema, unrelatedTicket, 2, "REVIEW_STARTED", "SYSTEM", null, "M2", null);
            insertActivity(database, schema, unrelatedTicket, 3, "REVIEW_STARTED", "EXTERNAL_USER",
                    "external-opaque", "M6", null);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertActivity(database, schema, unrelatedTicket, 4, "REVIEW_STARTED",
                            "UNKNOWN", "unknown", "M2", null));

            insertMessage(database, schema, unrelatedTicket, "SYSTEM", null, 1);
            insertMessage(database, schema, unrelatedTicket, "EXTERNAL_USER", "external-opaque", 2);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertMessage(database, schema, unrelatedTicket, "UNKNOWN", "unknown", 3));

            database.update("INSERT INTO " + schema + ".ticket_cancellations "
                            + "(id, ticket_id, reason_code, cancelled_by_type, cancelled_by_id, "
                            + "cancelled_by_module_id, cancelled_at) "
                            + "VALUES (?, ?, 'INFO_TIMEOUT', 'SYSTEM', NULL, 'M2', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), unrelatedTicket);
            database.update("INSERT INTO " + schema + ".ticket_cancellations "
                            + "(id, ticket_id, reason_code, cancelled_by_type, cancelled_by_id, "
                            + "cancelled_by_module_id, cancelled_at) "
                            + "VALUES (?, ?, 'INFO_TIMEOUT', 'EXTERNAL_USER', 'USR-M6-77', 'M6', CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), anonymousTicket);
            assertEquals("EXTERNAL_USER|USR-M6-77|M6", scalar(database,
                    "SELECT cancelled_by_type || '|' || cancelled_by_id || '|' || cancelled_by_module_id "
                            + "FROM " + schema + ".ticket_cancellations WHERE ticket_id='" + anonymousTicket + "'"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> database.update("INSERT INTO " + schema + ".ticket_cancellations "
                                    + "(id, ticket_id, reason_code, cancelled_by_type, cancelled_by_id, "
                                    + "cancelled_by_module_id, cancelled_at) "
                                    + "VALUES (?, ?, 'INFO_TIMEOUT', 'UNKNOWN', 'external', 'M6', CURRENT_TIMESTAMP)",
                            UUID.randomUUID(), identifiedTicket));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v10AbortsBeforeChangesWhenHistoricalStaffIdsNeedAnExternalMapping() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "9").migrate();
            UUID ticketId = insertTicket(database, schema, UUID.randomUUID(), "STAFF");
            database.update("INSERT INTO " + schema + ".information_requests "
                            + "(id, ticket_id, requested_by_module_id, requested_by_actor_type, requested_by_actor_id, "
                            + "message_for_citizen, resume_status, status, requested_at, due_at) "
                            + "VALUES (?, ?, 'M2', 'AGENT', 'm1-old-subject', 'Dato', 'IN_PROGRESS', 'PENDING', "
                            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                    UUID.randomUUID(), ticketId);
            insertActivity(database, schema, ticketId, 1, "INFORMATION_REQUIRED", "AGENT",
                    "m1-old-subject", "M2", null);

            FlywayException exception = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

            assertTrue(allMessages(exception).contains(
                    "V10 no puede convertir de manera segura actor IDs históricos AGENT/ADMIN sin mapping subjectId->citizenId"));
            assertEquals("m1-old-subject", scalar(database, "SELECT requested_by_actor_id FROM " + schema
                    + ".information_requests WHERE ticket_id='" + ticketId + "'"));
            assertFalse(constraintDefinition(database, schema, "ck_ticket_activity_actor_type")
                    .contains("EXTERNAL_USER"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(String schema, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private UUID insertTicket(JdbcTemplate database, String schema, UUID citizenId, String suffix) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, ?, ?, id, ticket_type, responsible_area_id, 'Resumen', 'Descripción', "
                        + "'{}'::jsonb, 'REGISTERED', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                id, "OP-" + suffix, "HASH-" + suffix, citizenId, citizenId == null);
        return id;
    }

    private void insertActivity(JdbcTemplate database, String schema, UUID ticketId, int sequence,
                                String actionType, String actorType, String actorId, String moduleId,
                                String reasonCode) {
        database.update("INSERT INTO " + schema + ".ticket_activities "
                        + "(ticket_id, sequence, action_type, actor_type, actor_id, source_module_id, reason_code, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                ticketId, sequence, actionType, actorType, actorId, moduleId, reasonCode);
    }

    private void insertAnsweredRequest(JdbcTemplate database, String schema, UUID ticketId, String answeredById) {
        database.update("INSERT INTO " + schema + ".information_requests "
                        + "(id, ticket_id, requested_by_module_id, requested_by_actor_type, requested_by_actor_id, "
                        + "message_for_citizen, resume_status, status, requested_at, due_at, response_message, "
                        + "answered_by_type, answered_by_id, answered_at) "
                        + "VALUES (?, ?, 'M2', 'CITIZEN', 'requester', 'Dato', 'IN_PROGRESS', 'ANSWERED', "
                        + "CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP + INTERVAL '1 hour', "
                        + "'Respuesta', 'CITIZEN', ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), ticketId, answeredById);
    }

    private void insertMessage(JdbcTemplate database, String schema, UUID ticketId, String actorType,
                               String actorId, int suffix) {
        database.update("INSERT INTO " + schema + ".ticket_messages "
                        + "(ticket_id, author_type, author_id, source_module_id, visibility, text, created_at) "
                        + "VALUES (?, ?, ?, 'M2', 'INTERNAL', ?, CURRENT_TIMESTAMP)",
                ticketId, actorType, actorId, "message-" + suffix);
    }

    private String activityActorId(JdbcTemplate database, String schema, UUID ticketId, int sequence) {
        return scalar(database, "SELECT actor_id FROM " + schema + ".ticket_activities WHERE ticket_id='"
                + ticketId + "' AND sequence=" + sequence);
    }

    private String activityActor(JdbcTemplate database, String schema, UUID ticketId, int sequence) {
        return scalar(database, "SELECT actor_type || '|' || COALESCE(actor_id, '<null>') || '|' || "
                + "COALESCE(source_module_id, '<null>') FROM " + schema + ".ticket_activities WHERE ticket_id='"
                + ticketId + "' AND sequence=" + sequence);
    }

    private String constraintDefinition(JdbcTemplate database, String schema, String name) {
        return database.queryForObject("SELECT pg_get_constraintdef(constraint_row.oid) FROM pg_constraint constraint_row "
                        + "JOIN pg_namespace namespace_row ON namespace_row.oid=constraint_row.connamespace "
                        + "WHERE namespace_row.nspname=? AND constraint_row.conname=?",
                String.class, schema, name);
    }

    private String scalar(JdbcTemplate database, String sql) {
        return database.queryForObject(sql, String.class);
    }

    private String temporarySchema() {
        return "f08_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String allMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }
}
