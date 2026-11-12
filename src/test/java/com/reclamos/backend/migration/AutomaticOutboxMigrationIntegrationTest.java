package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("dev")
class AutomaticOutboxMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate db;
    @Test void v50KeepsAllV49DataAndLeavesEveryHistoricalTicketAndEventUnapproved() {
        String schema="outbox50_"+UUID.randomUUID().toString().replace("-","");
        try {
            flyway(schema,"49").migrate(); UUID citizen=UUID.randomUUID(),ticket=UUID.randomUUID();
            Long owner=db.queryForObject("INSERT INTO "+schema+".module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,area_id,active,last_synced_at,created_at,updated_at) "
                    +"VALUES (?,'CIUDADANO','M1',?,'Test','Historical','AREA_RESPONSIBLE','M6',false,now(),now(),now()) RETURNING id",Long.class,citizen,citizen);
            db.update("INSERT INTO "+schema+".tickets(id,public_id,tracking_code_hash,citizen_id,is_anonymous,assigned_agent_id,request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,status_changed_at,created_at,updated_at) "
                    +"SELECT ?,?,?,?,false,?,id,ticket_type,'M6','Historical','Historical','{}'::jsonb,'ROUTED','LOW',now(),now(),now() FROM "+schema+".request_types ORDER BY id LIMIT 1",ticket,"T-"+ticket,"hash-"+ticket,citizen,owner);
            for(String status:List.of("PENDING","PUBLISHED","FAILED"))
                db.update("INSERT INTO "+schema+".outbox_events(event_id,event_type,ticket_id,payload,status,retry_count,next_attempt_at,last_failure_code) VALUES (?,'ticketCreated',?,'{\"unchanged\":true}'::jsonb,?,2,now(),'BROKER_UNAVAILABLE')",UUID.randomUUID(),ticket,status);
            db.update("INSERT INTO "+schema+".inbox_events(event_id,event_type,producer_module_id,received_at,status,payload_hash) VALUES (?,'citizenUpdated','M1',now(),'PROCESSED',?)",UUID.randomUUID(),"a".repeat(64));
            Map<String,String> before=new HashMap<>();
            for(String table:List.of("module_users","tickets","outbox_events","inbox_events")) before.put(table,snapshot(schema,table));
            var migration=flyway(schema,"50"); migration.migrate(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            for(String table:before.keySet()) assertThat(snapshot(schema,table)).as(table).isEqualTo(before.get(table));
            assertThat(db.queryForObject("SELECT count(*) FROM "+schema+".outbox_events WHERE auto_publish_eligible",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM "+schema+".tickets WHERE core_origin_verified",Integer.class)).isZero();
        } finally { db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    }
    private Flyway flyway(String schema,String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").schemas(schema).defaultSchema(schema).target(version).load();
    }
    private String snapshot(String schema,String table) {
        String removed=table.equals("tickets")?" - 'core_origin_verified'":table.equals("outbox_events")?" - 'auto_publish_eligible'":"";
        return db.queryForObject("SELECT jsonb_agg(to_jsonb(t)"+removed+" ORDER BY to_jsonb(t)::text)::text FROM "+schema+"."+table+" t",String.class);
    }
}
