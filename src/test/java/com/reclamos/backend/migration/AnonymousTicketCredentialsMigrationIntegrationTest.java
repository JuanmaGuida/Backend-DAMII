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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class AnonymousTicketCredentialsMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void v37KeepsIdentifiedTicketsValidAndEnforcesAnonymousCredentialAndContactInvariants() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "37").migrate();
            assertEquals(254, database.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='tickets' "
                            + "AND column_name='anonymous_contact_value'",
                    Integer.class, schema));
            UUID citizenId = UUID.randomUUID();
            insertModuleUser(database, schema, citizenId);

            assertDoesNotThrow(() -> insertTicket(database, schema, citizenId, false,
                    null, null, null, "IDENTIFIED"));
            assertDoesNotThrow(() -> insertTicket(database, schema, null, true,
                    "$2a$10$anonymous", null, null, "ANONYMOUS_NO_CONTACT"));
            assertDoesNotThrow(() -> insertTicket(database, schema, null, true,
                    "$2a$10$anonymous", "EMAIL", "person@example.com", "ANONYMOUS_EMAIL"));

            assertThrows(DataIntegrityViolationException.class, () -> insertTicket(database, schema, null, true,
                    null, null, null, "ANONYMOUS_WITHOUT_PASSWORD"));
            assertThrows(DataIntegrityViolationException.class, () -> insertTicket(database, schema, citizenId, false,
                    null, "PHONE", "+54 11 4444-5555", "IDENTIFIED_WITH_CONTACT"));
            assertThrows(DataIntegrityViolationException.class, () -> insertTicket(database, schema, null, true,
                    "$2a$10$anonymous", "PHONE", null, "INCOMPLETE_CONTACT"));
            assertThrows(DataIntegrityViolationException.class, () -> insertTicket(database, schema, null, true,
                    "$2a$10$anonymous", "FAX", "1234567", "INVALID_CHANNEL"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v37RefusesToInventCredentialsForPreexistingAnonymousTickets() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "36").migrate();
            insertTicket(database, schema, null, true, null, null, null, "LEGACY_ANONYMOUS");

            FlywayException error = assertThrows(FlywayException.class, () -> flyway(schema, "37").migrate());
            assertTrue(error.getMessage().contains("V37") || error.getCause() != null);
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

    private void insertModuleUser(JdbcTemplate database, String schema, UUID citizenId) {
        database.update("INSERT INTO " + schema + ".module_users "
                        + "(citizen_id, first_name, last_name, role, active, last_synced_at, created_at, updated_at) "
                        + "VALUES (?, 'Ciudadano', 'Histórico', 'CITIZEN', TRUE, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", citizenId);
    }

    private void insertTicket(JdbcTemplate database, String schema, UUID citizenId, boolean anonymous,
                              String passwordHash, String channel, String value, String suffix) {
        String credentialColumns = passwordHash == null && channel == null && value == null
                && !columnExists(database, schema, "anonymous_access_password_hash")
                ? ""
                : ", anonymous_access_password_hash, anonymous_contact_channel, anonymous_contact_value";
        String credentialValues = credentialColumns.isEmpty() ? "" : ", ?, ?, ?";
        String sql = "INSERT INTO " + schema + ".tickets "
                + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                + "status_changed_at, created_at, updated_at" + credentialColumns + ") "
                + "SELECT ?, ?, ?, ?, ?, id, ticket_type, responsible_area_id, 'Resumen', 'Descripción', "
                + "'{}'::jsonb, 'REGISTERED', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP"
                + credentialValues + " FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'";
        if (credentialColumns.isEmpty()) {
            database.update(sql, UUID.randomUUID(), "OP-V37-" + suffix, "HASH-V37-" + suffix,
                    citizenId, anonymous);
        } else {
            database.update(sql, UUID.randomUUID(), "OP-V37-" + suffix, "HASH-V37-" + suffix,
                    citizenId, anonymous, passwordHash, channel, value);
        }
    }

    private boolean columnExists(JdbcTemplate database, String schema, String column) {
        return Boolean.TRUE.equals(database.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name='tickets' AND column_name=?)",
                Boolean.class, schema, column));
    }

    private String temporarySchema() {
        return "fb37_" + UUID.randomUUID().toString().replace("-", "");
    }
}
