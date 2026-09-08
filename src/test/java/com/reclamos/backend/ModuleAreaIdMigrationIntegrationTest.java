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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class ModuleAreaIdMigrationIntegrationTest {
    private static final String VALID_IDS = "'M1','M2','M3','M4','M5','M6','M7','M8','M9'";

    @Autowired
    private DataSource dataSource;


    @Test
    void cleanV1ToV9NormalizesTheWholeActiveCatalogAndAddsAllConstraints() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "9").migrate();
            Map<String, Integer> counts = new LinkedHashMap<>();
            database.queryForList("SELECT responsible_area_id, COUNT(*) AS total FROM " + schema
                            + ".request_types WHERE active GROUP BY responsible_area_id ORDER BY responsible_area_id")
                    .forEach(row -> counts.put((String) row.get("responsible_area_id"),
                            ((Number) row.get("total")).intValue()));

            assertEquals(Map.of(
                    "M1", 9,
                    "M2", 11,
                    "M3", 15,
                    "M4", 11,
                    "M5", 8,
                    "M6", 32,
                    "M7", 15,
                    "M8", 9), counts);
            assertEquals(110, count(database, "SELECT COUNT(*) FROM " + schema + ".request_types WHERE active"));
            assertEquals(0, count(database, "SELECT COUNT(*) FROM " + schema
                    + ".request_types WHERE responsible_area_id NOT IN (" + VALID_IDS + ")"));
            assertEquals(5, count(database, "SELECT COUNT(*) FROM " + schema + ".request_types WHERE code IN ("
                    + "'INFORMAR_UNA_CABLES_EXPUESTOS','INFORMAR_UNA_COLUMNA_DANADA',"
                    + "'INFORMAR_UNA_LUMINARIA_APAGADA','INFORMAR_UNA_LUMINARIA_INTERMITENTE',"
                    + "'SOLICITAR_NUEVA_ILUMINACION') AND responsible_area_id='M6'"));
            assertEquals(1, count(database, "SELECT COUNT(*) FROM " + schema + ".request_types "
                    + "WHERE code='RECLAMAR_POR_UNA_DERIVACION_INCORRECTA' AND responsible_area_id='M2'"));
            assertEquals(7, count(database, "SELECT COUNT(*) FROM pg_constraint c "
                    + "JOIN pg_namespace n ON n.oid=c.connamespace "
                    + "WHERE n.nspname=? AND c.contype='c' AND c.conname IN ("
                    + "'ck_request_type_responsible_area_namespace','ck_ticket_responsible_area_namespace',"
                    + "'ck_ticket_activity_source_module_namespace','ck_ticket_message_source_module_namespace',"
                    + "'ck_attachment_source_module_namespace','ck_information_request_module_namespace',"
                    + "'ck_ticket_cancellation_module_namespace')", schema));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v9MigratesInactiveHistoricalAndPartiallyNormalizedRowsAndEnforcesNullableSourceIds() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "8").migrate();
            database.update("UPDATE " + schema + ".request_types SET active=FALSE "
                    + "WHERE code='INFORMAR_UN_BACHE'");
            database.update("UPDATE " + schema + ".request_types SET responsible_area_id='M5' "
                    + "WHERE code='INFORMAR_UN_PAGO_NO_REGISTRADO'");

            UUID roadTicket = insertTicket(database, schema, "INFORMAR_UN_BACHE", "Obras Públicas", "ROAD");
            UUID lightingTicket = insertTicket(database, schema, "INFORMAR_UNA_LUMINARIA_APAGADA",
                    "Obras Públicas", "LIGHT");

            flyway(schema, "9").migrate();

            assertEquals("M3", scalar(database, "SELECT responsible_area_id FROM " + schema
                    + ".request_types WHERE code='INFORMAR_UN_BACHE'"));
            assertEquals(false, database.queryForObject("SELECT active FROM " + schema
                    + ".request_types WHERE code='INFORMAR_UN_BACHE'", Boolean.class));
            assertEquals("M5", scalar(database, "SELECT responsible_area_id FROM " + schema
                    + ".request_types WHERE code='INFORMAR_UN_PAGO_NO_REGISTRADO'"));
            assertEquals("M3", scalar(database, "SELECT responsible_area_id FROM " + schema
                    + ".tickets WHERE id=CAST('" + roadTicket + "' AS UUID)"));
            assertEquals("M6", scalar(database, "SELECT responsible_area_id FROM " + schema
                    + ".tickets WHERE id=CAST('" + lightingTicket + "' AS UUID)"));
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".request_types WHERE responsible_area_id NOT IN (" + VALID_IDS + ")", Integer.class));
            assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".tickets WHERE responsible_area_id NOT IN (" + VALID_IDS + ")", Integer.class));

            database.update("INSERT INTO " + schema + ".ticket_activities "
                            + "(ticket_id, sequence, action_type, actor_type, source_module_id, created_at) "
                            + "VALUES (?, 1, 'TICKET_CREATED', 'CITIZEN', NULL, CURRENT_TIMESTAMP)", roadTicket);
            database.update("UPDATE " + schema + ".ticket_activities SET source_module_id='M9' "
                    + "WHERE ticket_id=?", roadTicket);
            database.update("UPDATE " + schema + ".ticket_activities SET source_module_id=NULL "
                    + "WHERE ticket_id=?", roadTicket);
            assertThrows(DataIntegrityViolationException.class,
                    () -> database.update("UPDATE " + schema
                            + ".ticket_activities SET source_module_id='AREA-TRAFFIC' WHERE ticket_id=?", roadTicket));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v9AbortsClearlyWithoutConvertingAnUnknownValue() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "8").migrate();
            database.update("UPDATE " + schema + ".request_types SET responsible_area_id='UNKNOWN-AREA' "
                    + "WHERE code='INFORMAR_UN_BACHE'");

            FlywayException exception = assertThrows(FlywayException.class, () -> flyway(schema, "9").migrate());

            assertTrue(allMessages(exception).contains(
                    "V9 abortada: valor desconocido en request_types.responsible_area_id: UNKNOWN-AREA"));
            assertEquals("UNKNOWN-AREA", scalar(database, "SELECT responsible_area_id FROM " + schema
                    + ".request_types WHERE code='INFORMAR_UN_BACHE'"));
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

    private UUID insertTicket(JdbcTemplate database, String schema, String code, String area, String suffix) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO " + schema + ".tickets "
                        + "(id, public_id, tracking_code_hash, is_anonymous, request_type_id, ticket_type, "
                        + "responsible_area_id, summary, description, form_data, current_status, current_priority, "
                        + "status_changed_at, created_at, updated_at) "
                        + "SELECT ?, ?, ?, TRUE, id, ticket_type, ?, 'Resumen', 'Descripción', '{}'::jsonb, "
                        + "'REGISTERED', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                        + "FROM " + schema + ".request_types WHERE code=?",
                id, "OP-" + suffix, "HASH-" + suffix, area, code);
        return id;
    }

    private String scalar(JdbcTemplate database, String sql) {
        return database.queryForObject(sql, String.class);
    }

    private int count(JdbcTemplate database, String sql, Object... arguments) {
        return database.queryForObject(sql, Integer.class, arguments);
    }

    private String temporarySchema() {
        return "f05_" + UUID.randomUUID().toString().replace("-", "");
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
