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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("dev")
class RecoverableTrackingCodeMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate db;

    @Test
    void v46PreservesHistoricalIdentifiedAndAnonymousRowsWithoutBackfillAndEnforcesIntegrity() {
        String schema = "tracking46_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "45").migrate();
            UUID owner = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                    + "VALUES (?,'Tracking','Historical','CITIZEN',true,now(),now(),now())", owner);
            UUID identified = insert(schema, owner, false), anonymous = insert(schema, null, true);
            String before = snapshot(schema);
            var latest = flyway(schema, "46");
            latest.migrate();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            assertThat(snapshot(schema)).isEqualTo(before);
            assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".tickets WHERE tracking_code_ciphertext IS NULL AND tracking_code_nonce IS NULL AND tracking_code_key_id IS NULL", Integer.class)).isEqualTo(2);
            assertThat(db.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema=? AND table_name='tickets' AND column_name IN ('tracking_code_nonce','tracking_code_ciphertext')", String.class, schema))
                    .containsExactlyInAnyOrder("bytea", "bytea");

            for (int mask = 1; mask < 7; mask++) {
                int present = mask;
                assertThatThrownBy(() -> set(schema, identified, (present & 1) == 0 ? null : new byte[48],
                        (present & 2) == 0 ? null : new byte[12], (present & 4) == 0 ? null : "test-v1"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> set(schema, anonymous, new byte[48], new byte[12], "test-v1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (int size : new int[]{0, 47, 49}) {
                assertThatThrownBy(() -> set(schema, identified, new byte[size], new byte[12], "test-v1"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (int size : new int[]{0, 11, 13}) {
                assertThatThrownBy(() -> set(schema, identified, new byte[48], new byte[size], "test-v1"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalid : new String[]{"", " ", "invalid|id"}) {
                assertThatThrownBy(() -> set(schema, identified, new byte[48], new byte[12], invalid))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            set(schema, identified, new byte[48], new byte[12], "test-v1");
            UUID second = insert(schema, owner, false);
            assertThatThrownBy(() -> set(schema, second, new byte[48], new byte[12], "test-v1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            byte[] different = new byte[12]; different[0] = 1;
            set(schema, second, new byte[48], different, "test-v1");
            assertThatThrownBy(() -> db.update("UPDATE " + schema + ".tickets SET tracking_code_hash=(SELECT tracking_code_hash FROM " + schema + ".tickets WHERE id=?) WHERE id=?", identified, second))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // Old binaries can still write all-null material after this additive migration.
            assertThat(insert(schema, owner, false)).isNotNull();
        } finally {
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).target(MigrationVersion.fromVersion(target)).load();
    }
    private UUID insert(String schema, UUID owner, boolean anonymous) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".tickets(id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,status_changed_at,created_at,updated_at) "
                + "SELECT ?,?,?,?, ?,?, id,ticket_type,responsible_area_id,'Historical','Historical','{}'::jsonb,'REGISTERED','LOW',now(),now(),now() "
                + "FROM " + schema + ".request_types WHERE code='INFORMAR_UN_BACHE'", id, "TEST-"+id, "TEST-HASH-"+id, owner, anonymous, anonymous ? "test-password-hash" : null);
        return id;
    }
    private void set(String schema, UUID id, byte[] ciphertext, byte[] nonce, String keyId) {
        db.update("UPDATE " + schema + ".tickets SET tracking_code_ciphertext=?,tracking_code_nonce=?,tracking_code_key_id=? WHERE id=?", ciphertext, nonce, keyId, id);
    }
    private String snapshot(String schema) {
        return db.queryForObject("SELECT jsonb_agg(to_jsonb(t) - 'tracking_code_ciphertext' - 'tracking_code_nonce' - 'tracking_code_key_id' ORDER BY id)::text FROM " + schema + ".tickets t", String.class);
    }
}
