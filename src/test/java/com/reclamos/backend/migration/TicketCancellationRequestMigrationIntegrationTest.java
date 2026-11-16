package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("dev")
class TicketCancellationRequestMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test void upgradeFromV44PreservesCancellationAndEnforcesNewHistoryConstraints() {
        String schema="h05_upgrade_"+UUID.randomUUID().toString().replace("-","");
        JdbcTemplate db=new JdbcTemplate(dataSource);
        UUID ticket=UUID.randomUUID(),cancel=UUID.randomUUID(),request=UUID.randomUUID();
        try {
            migrate(schema,"44");
            db.update("INSERT INTO "+schema+".tickets (id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,is_escalated,status_changed_at,created_at,updated_at) SELECT ?,?, ?,NULL,TRUE,'migration-test-hash',id,ticket_type,responsible_area_id,'H05','Upgrade','{}'::jsonb,'CANCELLED','LOW',FALSE,now(),now(),now() FROM "+schema+".request_types WHERE code='INFORMAR_UN_BACHE'",ticket,"H05-"+ticket,"HASH-"+ticket);
            db.update("INSERT INTO "+schema+".ticket_cancellations(id,ticket_id,reason_code,cancelled_by_type,cancelled_at) VALUES (?,?,'OTHER','SYSTEM',now())",cancel,ticket);
            var previous=db.queryForMap("SELECT * FROM "+schema+".ticket_cancellations WHERE id=?",cancel);
            Flyway upgraded=migrate(schema,"45"); assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
            var current=db.queryForMap("SELECT * FROM "+schema+".ticket_cancellations WHERE id=?",cancel);
            current.remove("cancellation_scope");assertThat(current).isEqualTo(previous);
            db.update("INSERT INTO "+schema+".ticket_cancellation_requests(id,ticket_id,requester_kind,reason,reason_code,status,requested_at,duplicate_snapshot,requested_status_changed_at) VALUES (?,?,'ANONYMOUS','Retiro','OTHER','PENDING',now(),'[\"00000000-0000-0000-0000-000000000001\"]'::jsonb,now())",request,ticket);
            assertThatThrownBy(()->db.update("UPDATE "+schema+".ticket_cancellation_requests SET status='APPROVED',reviewed_at=now(),reviewed_by=?,cancellation_scope='GLOBAL',decision_reason='Motivo' WHERE id=?",UUID.randomUUID(),request)).hasMessageContaining("scope mismatch");
            assertThatThrownBy(()->db.update("UPDATE "+schema+".ticket_cancellation_requests SET requester_kind='IDENTIFIED' WHERE id=?",request)).hasMessageContaining("Immutable");
            assertThatThrownBy(()->db.update("UPDATE "+schema+".ticket_cancellations SET cancellation_scope='INVALID' WHERE id=?",cancel)).hasMessageContaining("ck_cancellation_scope");
            db.update("UPDATE "+schema+".ticket_cancellations SET cancellation_scope='GLOBAL' WHERE id=?",cancel);
            db.update("UPDATE "+schema+".ticket_cancellation_requests SET status='APPROVED',reviewed_at=now(),reviewed_by=?,cancellation_scope='GLOBAL',decision_reason='Motivo' WHERE id=?",UUID.randomUUID(),request);
            assertThatThrownBy(()->db.update("UPDATE "+schema+".ticket_cancellation_requests SET status='PENDING' WHERE id=?",request)).hasMessageContaining("Immutable");
            assertThatThrownBy(()->db.update("DELETE FROM "+schema+".ticket_cancellations WHERE id=?",cancel)).hasMessageContaining("scope mismatch");
        } finally { db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    }
    Flyway migrate(String schema,String version) {
        Flyway f=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").schemas(schema)
                .defaultSchema(schema).target(MigrationVersion.fromVersion(version)).load();f.migrate();return f;
    }
}
