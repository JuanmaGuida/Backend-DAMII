package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class InformationRequestFlowMigrationIntegrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void v39SupportsExternalRequesterAnonymousAnswerAndResponseAttachment() {
        String schema = "information_request_v39_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .cleanDisabled(false)
                    .load();
            flyway.migrate();
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("39", database.queryForObject("SELECT version FROM " + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class));

            UUID ticketId = insertAnonymousTicket(database, schema);
            UUID externalRequest = UUID.randomUUID();
            insertPending(database, schema, externalRequest, ticketId, "EXTERNAL_USER");
            assertDoesNotThrow(() -> database.update("UPDATE " + schema + ".information_requests SET "
                            + "status='ANSWERED', response_message=NULL, answered_by_type='CITIZEN', "
                            + "answered_by_id=NULL, answered_at=CURRENT_TIMESTAMP WHERE id=?", externalRequest));

            long attachmentId = database.queryForObject("INSERT INTO " + schema + ".attachments "
                    + "(ticket_id,file_name,content_type,size_bytes,storage_key,visibility,uploaded_by_type,uploaded_by_id) "
                    + "VALUES (?, 'proof.png', 'image/png', 1, ?, 'PUBLIC', 'CITIZEN', NULL) RETURNING id",
                    Long.class, ticketId, "tickets/" + ticketId + "/proof");
            assertDoesNotThrow(() -> database.update("INSERT INTO " + schema
                    + ".information_request_attachments (information_request_id,attachment_id,role) "
                    + "VALUES (?,?,'RESPONSE')", externalRequest, attachmentId));

            UUID systemRequest = UUID.randomUUID();
            assertDoesNotThrow(() -> insertPending(database, schema, systemRequest, ticketId, "SYSTEM"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertPending(database, schema, UUID.randomUUID(), ticketId, "EXTERNAL_USER"));
            database.update("UPDATE " + schema + ".information_requests SET status='ANSWERED', "
                    + "response_message='ok', answered_by_type='CITIZEN', answered_at=CURRENT_TIMESTAMP WHERE id=?",
                    systemRequest);
            UUID invalidRequest = UUID.randomUUID();
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertPending(database, schema, invalidRequest, ticketId, "UNKNOWN"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private UUID insertAnonymousTicket(JdbcTemplate database, String schema) {
        UUID ticketId = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,"
                        + "request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,"
                        + "current_priority,is_escalated,status_changed_at,created_at,updated_at) "
                        + "SELECT ?, ?, ?, NULL, TRUE, 'password-hash', id, ticket_type, 'M6', 'HU164', 'HU164', "
                        + "'{}'::jsonb, 'PENDING_INFORMATION', 'LOW', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                ticketId, "HU164-" + ticketId.toString().substring(0, 20), "hash-" + ticketId);
        return ticketId;
    }

    private void insertPending(JdbcTemplate database, String schema, UUID requestId, UUID ticketId,
                               String actorType) {
        OffsetDateTime requestedAt = OffsetDateTime.parse("2026-09-20T10:00:00Z");
        database.update("INSERT INTO " + schema + ".information_requests "
                        + "(id,ticket_id,requested_by_module_id,requested_by_actor_type,requested_by_actor_id,"
                        + "message_for_citizen,resume_status,status,requested_at,due_at) "
                        + "VALUES (?,?,'M6',?,'actor','Dato','IN_PROGRESS','PENDING',?,?)",
                requestId, ticketId, actorType, requestedAt, requestedAt.plusHours(1));
    }
}
