package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class SatisfactionSurveyMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void migrationCreatesSurveyTableWithRequiredConstraints() {
        String schema = "survey38_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).cleanDisabled(false)
                    .target(MigrationVersion.fromVersion("39")).load().migrate();

            assertEquals("bigint", column(database, schema, "id", "data_type"));
            assertEquals("uuid", column(database, schema, "ticket_id", "data_type"));
            assertEquals("smallint", column(database, schema, "score", "data_type"));
            assertEquals("NO", column(database, schema, "score", "is_nullable"));
            assertEquals("YES", column(database, schema, "comment", "is_nullable"));
            assertEquals("timestamp with time zone", column(database, schema, "created_at", "data_type"));
            assertEquals(1, constraintCount(database, schema, "FOREIGN KEY"));
            assertEquals(1, constraintCount(database, schema, "UNIQUE"));
            assertTrue(Objects.requireNonNull(database.queryForObject("SELECT pg_get_constraintdef(c.oid) "
                            + "FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace "
                            + "WHERE n.nspname=? AND c.conname='ck_satisfaction_survey_score'",
                    String.class, schema)).contains("score >= 1"));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private String column(JdbcTemplate database, String schema, String column, String property) {
        return database.queryForObject("SELECT " + property + " FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name='satisfaction_surveys' AND column_name=?",
                String.class, schema, column);
    }

    private int constraintCount(JdbcTemplate database, String schema, String type) {
        return database.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema=? AND table_name='satisfaction_surveys' AND constraint_type=?",
                Integer.class, schema, type);
    }
}