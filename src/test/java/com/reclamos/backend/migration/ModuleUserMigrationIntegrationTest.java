package com.reclamos.backend.migration;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ModuleUserMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void cleanV1ToV18CreatesModuleUsersAndAlignsAssignedAgent() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "18").migrate();

            List<String> columns = database.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='module_users'",
                    String.class, schema);
            assertTrue(columns.containsAll(List.of("id", "citizen_id", "first_name", "last_name", "email", "phone",
                    "profile_image_url", "role", "area_id", "preferred_notification_channel", "active",
                    "last_synced_at", "created_at", "updated_at")));
            assertFalse(columns.stream().anyMatch(name -> name.contains("password") || name.contains("token")));

            UUID citizenId = UUID.randomUUID();
            insertModuleUser(database, schema, citizenId, "CITIZEN", null);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertModuleUser(database, schema, citizenId, "CITIZEN", null));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertModuleUser(database, schema, UUID.randomUUID(), "AREA_RESPONSIBLE", null));

            Map<String, Object> assignedAgentColumn = database.queryForMap(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='tickets' AND column_name='assigned_agent_id'",
                    schema);
            assertEquals("bigint", assignedAgentColumn.get("data_type"));
            assertEquals("YES", assignedAgentColumn.get("is_nullable"));
            assertEquals("module_users", database.queryForObject(
                    "SELECT foreign_table.table_name FROM information_schema.referential_constraints relation "
                            + "JOIN information_schema.table_constraints source_constraint "
                            + "ON source_constraint.constraint_schema=relation.constraint_schema "
                            + "AND source_constraint.constraint_name=relation.constraint_name "
                            + "JOIN information_schema.table_constraints target_constraint "
                            + "ON target_constraint.constraint_schema=relation.unique_constraint_schema "
                            + "AND target_constraint.constraint_name=relation.unique_constraint_name "
                            + "JOIN information_schema.tables foreign_table "
                            + "ON foreign_table.table_schema=target_constraint.table_schema "
                            + "AND foreign_table.table_name=target_constraint.table_name "
                            + "WHERE source_constraint.constraint_schema=? "
                            + "AND source_constraint.constraint_name='fk_ticket_assigned_agent'",
                    String.class, schema));
            assertEquals(0, countCitizenForeignKeys(database, schema));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v17WithIdentifiedTicketAndNullAssignmentMigratesWithoutInventingUsers() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "17").migrate();
            UUID citizenId = UUID.randomUUID();
            UUID ticketId = insertTicket(database, schema, citizenId, "V18-SAFE");

            flyway(schema, "18").migrate();

            assertEquals(citizenId, database.queryForObject(
                    "SELECT citizen_id FROM " + schema + ".tickets WHERE id=?", UUID.class, ticketId));
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM " + schema + ".module_users", Integer.class));
            assertEquals(0, countCitizenForeignKeys(database, schema));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v18AbortsBeforeConversionWhenAssignedAgentContainsUnmappableData() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "17").migrate();
            UUID ticketId = insertTicket(database, schema, UUID.randomUUID(), "V18-GUARD");
            database.update("UPDATE " + schema + ".tickets SET assigned_agent_id='legacy-agent' WHERE id=?", ticketId);

            FlywayException exception = assertThrows(FlywayException.class, () -> flyway(schema, "18").migrate());

            assertTrue(allMessages(exception).contains(
                    "V18 no puede convertir tickets.assigned_agent_id a BIGINT"));
            assertEquals("legacy-agent", database.queryForObject(
                    "SELECT assigned_agent_id FROM " + schema + ".tickets WHERE id=?", String.class, ticketId));
            assertEquals("character varying", database.queryForObject(
                    "SELECT data_type FROM information_schema.columns WHERE table_schema=? "
                            + "AND table_name='tickets' AND column_name='assigned_agent_id'",
                    String.class, schema));
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
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private void insertModuleUser(JdbcTemplate database, String schema, UUID citizenId, String role, String areaId) {
        database.update("INSERT INTO " + schema + ".module_users "
                        + "(citizen_id, first_name, last_name, role, area_id, active, last_synced_at, created_at, updated_at) "
                        + "VALUES (?, 'Nombre', 'Apellido', ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                citizenId, role, areaId);
    }

    private UUID insertTicket(JdbcTemplate database, String schema, UUID citizenId, String suffix) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, ?, FALSE, id, ticket_type, responsible_area_id, 'Resumen', 'Descripción', "
                        + "'{}'::jsonb, 'REGISTERED', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                id, "OP-" + suffix, "HASH-" + suffix, citizenId);
        return id;
    }

    private int countCitizenForeignKeys(JdbcTemplate database, String schema) {
        return database.queryForObject(
                "SELECT COUNT(*) FROM information_schema.key_column_usage columns "
                        + "JOIN information_schema.table_constraints constraints "
                        + "ON constraints.constraint_schema=columns.constraint_schema "
                        + "AND constraints.constraint_name=columns.constraint_name "
                        + "WHERE columns.table_schema=? AND columns.table_name='tickets' "
                        + "AND columns.column_name='citizen_id' AND constraints.constraint_type='FOREIGN KEY'",
                Integer.class, schema);
    }

    private String temporarySchema() {
        return "fb1_" + UUID.randomUUID().toString().replace("-", "");
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
