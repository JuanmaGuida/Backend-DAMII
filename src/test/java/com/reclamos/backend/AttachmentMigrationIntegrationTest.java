package com.reclamos.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class AttachmentMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v8CreatesAttachmentTableColumnsAndIndex() {
        assertEquals("attachments", jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.attachments')::TEXT", String.class));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='attachments' "
                + "AND column_name='uploaded_by_id' AND is_nullable='YES'"));
        assertEquals(1, count("SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' "
                + "AND tablename='attachments' AND indexname='idx_attachment_ticket_created'"));
    }

    @Test
    void v8CreatesRequiredIntegrityConstraints() {
        assertEquals(1, constraintCount("fk_attachment_ticket", "f"));
        assertEquals(1, constraintCount("uk_attachment_storage_key", "u"));
        assertEquals(1, constraintCount("ck_attachment_size", "c"));
        assertEquals(1, constraintCount("ck_attachment_visibility", "c"));
        assertEquals(1, constraintCount("ck_attachment_uploaded_by_type", "c"));

        String actorConstraint = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname='ck_attachment_uploaded_by_type'",
                String.class);
        for (String actor : new String[]{"CITIZEN", "AGENT", "AREA_RESPONSIBLE", "ADMIN",
                "EXTERNAL_USER", "SYSTEM"}) {
            assertTrue(actorConstraint.contains(actor));
        }
    }

    private int constraintCount(String name, String type) {
        return count("SELECT COUNT(*) FROM pg_constraint WHERE conname='" + name + "' AND contype='" + type + "'");
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}
