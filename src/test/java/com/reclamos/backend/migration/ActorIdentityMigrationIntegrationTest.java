package com.reclamos.backend.migration;

import com.reclamos.backend.service.TrackingCodeCipher;
import com.reclamos.backend.service.TrackingCodeService;
import org.flywaydb.core.Flyway;
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
class ActorIdentityMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate db;
    @Autowired TrackingCodeCipher cipher;
    @Autowired TrackingCodeService tracking;

    @Test
    void v47PreservesUsersAssignmentsOwnershipAndEncryptedTrackingWhileBackfillingMockActors() {
        String schema = "identity47_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, "46").migrate();
            UUID citizen = UUID.randomUUID();
            Long userId = db.queryForObject("INSERT INTO " + schema + ".module_users "
                    + "(citizen_id,first_name,last_name,email,role,area_id,preferred_notification_channel,active,last_synced_at,created_at,updated_at) "
                    + "VALUES (?,'Historical','Staff','old@example.test','AREA_RESPONSIBLE','M6','EMAIL',false,now(),now(),now()) RETURNING id",
                    Long.class, citizen);
            UUID identified = ticket(schema, citizen, false, userId);
            ticket(schema, null, true, null);
            String code = tracking.generate();
            var encrypted = cipher.encrypt(identified, citizen, code);
            db.update("UPDATE " + schema + ".tickets SET tracking_code_hash=?,tracking_code_ciphertext=?,tracking_code_nonce=?,tracking_code_key_id=? WHERE id=?",
                    tracking.hash(code), encrypted.ciphertext(), encrypted.nonce(), encrypted.keyId(), identified);
            db.update("INSERT INTO " + schema + ".notification_logs(ticket_id,citizen_id,type,channel,status,attempt_count) "
                    + "VALUES (?,?,'TICKET_REGISTERED','EMAIL','PENDING',0)", identified, citizen);
            String usersBefore = userSnapshot(schema);
            String ticketsBefore = snapshot(schema, "tickets");

            Flyway latest = flyway(schema, "47");
            latest.migrate();

            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            assertThat(userSnapshot(schema)).isEqualTo(usersBefore);
            assertThat(snapshot(schema, "tickets")).isEqualTo(ticketsBefore);
            assertThat(db.queryForObject("SELECT actor_id FROM " + schema + ".module_users WHERE id=?", UUID.class, userId)).isEqualTo(citizen);
            assertThat(db.queryForObject("SELECT actor_type || '/' || identity_source FROM " + schema + ".module_users WHERE id=?", String.class, userId))
                    .isEqualTo("CIUDADANO/MOCK");
            assertThat(db.queryForObject("SELECT recipient_actor_id FROM " + schema + ".notification_logs WHERE ticket_id=?", UUID.class, identified))
                    .isEqualTo(citizen);
            assertThat(cipher.decrypt(identified, citizen, tracking.hash(code), encrypted)).isEqualTo(code);

            UUID employee1 = UUID.randomUUID(), employee2 = UUID.randomUUID();
            insertIdentity(schema, employee1, "EMPLEADO", null, "AGENT");
            insertIdentity(schema, employee2, "EMPLEADO", null, "ADMIN");
            assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".module_users WHERE citizen_id IS NULL", Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> insertIdentity(schema, employee1, "EMPLEADO", null, "AGENT"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertIdentity(schema, UUID.randomUUID(), "EMPLEADO", null, "CITIZEN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertIdentity(schema, UUID.randomUUID(), "CIUDADANO", null, "CITIZEN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertIdentity(schema, UUID.randomUUID(), "CIUDADANO", citizen, "CITIZEN"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertIdentity(schema, UUID.randomUUID(), "EMPLEADO", UUID.randomUUID(), "AGENT"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertIdentity(schema, UUID.randomUUID(), "UNKNOWN", null, "AGENT"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertIdentity(schema, null, "EMPLEADO", null, "AGENT"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).target(target).load();
    }

    private UUID ticket(String schema, UUID owner, boolean anonymous, Long assigned) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".tickets "
                + "(id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,assigned_agent_id,request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,status_changed_at,created_at,updated_at) "
                + "SELECT ?,?,?,?,?,?,?,id,ticket_type,responsible_area_id,'Historical','Historical','{}'::jsonb,'REGISTERED','LOW',now(),now(),now() "
                + "FROM " + schema + ".request_types ORDER BY id LIMIT 1",
                id, "TK-TEST-" + id, "hash-" + id, owner, anonymous, anonymous ? "test-password-hash" : null, assigned);
        return id;
    }

    private void insertIdentity(String schema, UUID actor, String type, UUID citizen, String role) {
        db.update("INSERT INTO " + schema + ".module_users "
                + "(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?,?,'M1',?,'Test','Identity',?,true,now(),now(),now())", actor, type, citizen, role);
    }

    private String userSnapshot(String schema) {
        return db.queryForObject("SELECT jsonb_agg(to_jsonb(u) - 'actor_id' - 'actor_type' - 'identity_source' ORDER BY id)::text FROM "
                + schema + ".module_users u", String.class);
    }

    private String snapshot(String schema, String table) {
        return db.queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY id)::text FROM " + schema + "." + table + " t", String.class);
    }
}
