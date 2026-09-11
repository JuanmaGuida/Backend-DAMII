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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class TicketIntegrityMigrationIntegrationTest {
    private static final UUID DEV_CITIZEN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Autowired
    private DataSource dataSource;

    @Test
    void cleanV1ToV20EnforcesTicketCitizenIntegrityAndNormalizesIndexes() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema).migrate();

            assertEquals("20", database.queryForObject(
                    "SELECT version FROM " + schema
                            + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class));

            UUID citizenId = UUID.randomUUID();
            insertModuleUser(database, schema, citizenId);
            assertDoesNotThrow(() -> insertTicket(database, schema, citizenId, false, "IDENTIFIED"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertTicket(database, schema, UUID.randomUUID(), false, "ORPHAN"));
            assertDoesNotThrow(() -> insertTicket(database, schema, null, true, "ANONYMOUS"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertModuleUser(database, schema, citizenId));

            assertCitizenForeignKey(database, schema);
            assertResolutionConfirmationIndex(database, schema);
            assertActivitySequenceIndexes(database, schema);
            assertResolutionQueryCanUseIndex(database, schema);
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void devSeedProvidesTheKnownCitizenProjection() {
        JdbcTemplate database = new JdbcTemplate(dataSource);
        assertEquals(1, database.queryForObject(
                "SELECT COUNT(*) FROM module_users WHERE citizen_id=? AND role='CITIZEN'",
                Integer.class, DEV_CITIZEN_ID));
    }

    private void assertCitizenForeignKey(JdbcTemplate database, String schema) {
        Map<String, Object> foreignKey = database.queryForMap(
                "SELECT source_column.column_name AS source_column, target_table.table_name AS target_table, "
                        + "target_column.column_name AS target_column, relation.delete_rule "
                        + "FROM information_schema.referential_constraints relation "
                        + "JOIN information_schema.table_constraints source_constraint "
                        + "ON source_constraint.constraint_schema=relation.constraint_schema "
                        + "AND source_constraint.constraint_name=relation.constraint_name "
                        + "JOIN information_schema.key_column_usage source_column "
                        + "ON source_column.constraint_schema=source_constraint.constraint_schema "
                        + "AND source_column.constraint_name=source_constraint.constraint_name "
                        + "JOIN information_schema.table_constraints target_constraint "
                        + "ON target_constraint.constraint_schema=relation.unique_constraint_schema "
                        + "AND target_constraint.constraint_name=relation.unique_constraint_name "
                        + "JOIN information_schema.key_column_usage target_column "
                        + "ON target_column.constraint_schema=target_constraint.constraint_schema "
                        + "AND target_column.constraint_name=target_constraint.constraint_name "
                        + "AND target_column.ordinal_position=source_column.position_in_unique_constraint "
                        + "JOIN information_schema.tables target_table "
                        + "ON target_table.table_schema=target_constraint.table_schema "
                        + "AND target_table.table_name=target_constraint.table_name "
                        + "WHERE source_constraint.constraint_schema=? "
                        + "AND source_constraint.table_name='tickets' "
                        + "AND source_constraint.constraint_name='fk_ticket_citizen_module_user'",
                schema);
        assertEquals("citizen_id", foreignKey.get("source_column"));
        assertEquals("module_users", foreignKey.get("target_table"));
        assertEquals("citizen_id", foreignKey.get("target_column"));
        assertEquals("NO ACTION", foreignKey.get("delete_rule"));
    }

    private void assertResolutionConfirmationIndex(JdbcTemplate database, String schema) {
        String definition = database.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname=? "
                        + "AND tablename='tickets' AND indexname='idx_ticket_resolution_confirmation_due_at'",
                String.class, schema);
        assertNotNull(definition);
        assertTrue(definition.contains("(resolution_confirmation_due_at)"));
        assertTrue(definition.contains("current_status"));
        assertTrue(definition.contains("RESOLVED"));
        assertTrue(definition.contains("resolution_confirmation_due_at IS NOT NULL"));
    }

    private void assertActivitySequenceIndexes(JdbcTemplate database, String schema) {
        List<Map<String, Object>> indexes = database.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname=? "
                        + "AND tablename='ticket_activities' AND indexdef LIKE '%(ticket_id, sequence)%'",
                schema);
        assertEquals(1, indexes.size());
        assertEquals("uk_ticket_activity_sequence", indexes.getFirst().get("indexname"));
        assertTrue(((String) indexes.getFirst().get("indexdef")).contains("UNIQUE INDEX"));
    }

    private void assertResolutionQueryCanUseIndex(JdbcTemplate database, String schema) {
        database.execute("SET enable_seqscan TO off");
        try {
            String plan = String.join("\n", database.queryForList(
                    "EXPLAIN SELECT id FROM " + schema + ".tickets "
                            + "WHERE current_status='RESOLVED' "
                            + "AND resolution_confirmation_due_at IS NOT NULL "
                            + "AND resolution_confirmation_due_at <= CURRENT_TIMESTAMP",
                    String.class));
            assertTrue(plan.contains("idx_ticket_resolution_confirmation_due_at"), plan);
        } finally {
            database.execute("RESET enable_seqscan");
        }
    }

    private Flyway flyway(String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion("20"))
                .load();
    }

    private void insertModuleUser(JdbcTemplate database, String schema, UUID citizenId) {
        database.update("INSERT INTO " + schema + ".module_users "
                        + "(citizen_id, first_name, last_name, role, active, last_synced_at, created_at, updated_at) "
                        + "VALUES (?, 'Ciudadano', 'Estructural', 'CITIZEN', TRUE, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                citizenId);
    }

    private void insertTicket(JdbcTemplate database, String schema, UUID citizenId, boolean anonymous, String suffix) {
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, citizen_id, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, ?, ?, id, ticket_type, responsible_area_id, 'Resumen', 'Descripción', "
                        + "'{}'::jsonb, 'REGISTERED', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'",
                UUID.randomUUID(), "OP-V20-" + suffix, "HASH-V20-" + suffix, citizenId, anonymous);
    }

    private String temporarySchema() {
        return "fb2_" + UUID.randomUUID().toString().replace("-", "");
    }
}
