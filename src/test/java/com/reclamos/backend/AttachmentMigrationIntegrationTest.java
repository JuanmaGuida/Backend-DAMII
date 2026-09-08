package com.reclamos.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class AttachmentMigrationIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void v8CreatesAttachmentTableColumnsAndIndex() {
        inV8Schema((database, schema) -> {
            assertEquals(1, count(database, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=? AND table_name='attachments'", schema));
            assertEquals(1, count(database, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name='attachments' "
                    + "AND column_name='uploaded_by_id' AND is_nullable='YES'", schema));
            assertEquals(1, count(database, "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=? "
                    + "AND tablename='attachments' AND indexname='idx_attachment_ticket_created'", schema));
        });
    }

    @Test
    void v8CreatesRequiredIntegrityConstraints() {
        inV8Schema((database, schema) -> {
            assertEquals(1, constraintCount(database, schema, "fk_attachment_ticket", "f"));
            assertEquals(1, constraintCount(database, schema, "uk_attachment_storage_key", "u"));
            assertEquals(1, constraintCount(database, schema, "ck_attachment_size", "c"));
            assertEquals(1, constraintCount(database, schema, "ck_attachment_visibility", "c"));
            assertEquals(1, constraintCount(database, schema, "ck_attachment_uploaded_by_type", "c"));

            String actorConstraint = database.queryForObject(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                            + "JOIN pg_namespace n ON n.oid=c.connamespace "
                            + "WHERE n.nspname=? AND c.conname='ck_attachment_uploaded_by_type'",
                    String.class, schema);
            for (String actor : new String[]{"CITIZEN", "AGENT", "AREA_RESPONSIBLE", "ADMIN",
                    "EXTERNAL_USER", "SYSTEM"}) {
                assertTrue(actorConstraint.contains(actor));
            }
        });
    }

    private int constraintCount(JdbcTemplate database, String schema, String name, String type) {
        return count(database, "SELECT COUNT(*) FROM pg_constraint c "
                + "JOIN pg_namespace n ON n.oid=c.connamespace "
                + "WHERE n.nspname=? AND c.conname=? AND c.contype=?", schema, name, type);
    }

    private int count(JdbcTemplate database, String sql, Object... arguments) {
        return database.queryForObject(sql, Integer.class, arguments);
    }

    private void inV8Schema(SchemaAssertion assertion) {
        String schema = "attachment_v8_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target(MigrationVersion.fromVersion("8"))
                    .load()
                    .migrate();
            assertion.verify(database, schema);
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @FunctionalInterface
    private interface SchemaAssertion {
        void verify(JdbcTemplate database, String schema);
    }
}
