package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("dev")
class CoreInboxMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate db;
    @Test void v49PreservesV48HistoryPermissionsAndRelationshipsWhileAllowingPartialM1Citizens() {
        String schema="inbox49_"+UUID.randomUUID().toString().replace("-","");
        try {
            flyway(schema,"48").migrate();
            UUID citizen=UUID.randomUUID(), ticket=UUID.randomUUID(), event=UUID.randomUUID();
            Long user=db.queryForObject("INSERT INTO "+schema+".module_users "
                    +"(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,area_id,preferred_notification_channel,active,last_synced_at,created_at,updated_at) "
                    +"VALUES (?,'CIUDADANO','MOCK',?,'Historical','Staff','AREA_RESPONSIBLE','M6','EMAIL',false,now(),now(),now()) RETURNING id",Long.class,citizen,citizen);
            db.update("INSERT INTO "+schema+".tickets "
                    +"(id,public_id,tracking_code_hash,citizen_id,is_anonymous,assigned_agent_id,request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,status_changed_at,created_at,updated_at) "
                    +"SELECT ?,?,?,?,false,?,id,ticket_type,'M6','Historical','Historical','{}'::jsonb,'ROUTED','LOW',now(),now(),now() FROM "+schema+".request_types ORDER BY id LIMIT 1",
                    ticket,"T-"+ticket,"hash-"+ticket,citizen,user);
            db.update("INSERT INTO "+schema+".outbox_events(event_id,event_type,ticket_id,payload,status,retry_count,next_attempt_at,last_failure_code) VALUES (?,'ticketCreated',?,'{}'::jsonb,'PENDING',2,now(),'BROKER_UNAVAILABLE')",UUID.randomUUID(),ticket);
            db.update("INSERT INTO "+schema+".inbox_events(event_id,event_type,producer_module_id,received_at,processed_at,status,payload) VALUES (?,'updateTicketStatus','M6',now(),now(),'PROCESSED','{}'::jsonb)",event);
            Map<String,String> before=new HashMap<>();
            for(String table:List.of("module_users","tickets","outbox_events","inbox_events")) before.put(table,snapshot(schema,table));
            var migration=flyway(schema,"49"); migration.migrate(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            for(String table:before.keySet()) assertThat(snapshot(schema,table)).as(table).isEqualTo(before.get(table));
            assertThat(db.queryForObject("SELECT external_status FROM "+schema+".module_users WHERE id=?",String.class,user)).isEqualTo("UNKNOWN");
            assertThat(db.queryForObject("SELECT payload_hash FROM "+schema+".inbox_events WHERE event_id=?",String.class,event)).isNull();
            UUID partial=UUID.randomUUID();
            db.update("INSERT INTO "+schema+".module_users(actor_id,actor_type,identity_source,citizen_id,role,active,last_synced_at,created_at,updated_at) VALUES (?,'CIUDADANO','M1',?,'CITIZEN',true,now(),now(),now())",partial,partial);
            assertThatThrownBy(() -> db.update("UPDATE "+schema+".module_users SET first_name=NULL WHERE id=?",user)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE "+schema+".module_users SET first_name='' WHERE actor_id=?",partial)).isInstanceOf(DataIntegrityViolationException.class);
            db.update("INSERT INTO "+schema+".inbox_events(event_id,event_type,producer_module_id,received_at,status) VALUES (?,'citizenUpdated','M1',now(),'PROCESSED')",UUID.randomUUID());
        } finally { db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    }
    private Flyway flyway(String schema,String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").schemas(schema).defaultSchema(schema).target(target).load();
    }
    private String snapshot(String schema,String table) {
        String additions=switch(table) {
            case "module_users" -> " - 'external_status' - 'external_profile_updated_at' - 'external_status_updated_at' - 'external_address_updated_at'";
            case "tickets" -> " - 'external_update_at'";
            case "inbox_events" -> " - 'payload_hash'";
            default -> "";
        };
        return db.queryForObject("SELECT jsonb_agg(to_jsonb(t)"+additions+" ORDER BY to_jsonb(t)::text)::text FROM "+schema+"."+table+" t",String.class);
    }
}
