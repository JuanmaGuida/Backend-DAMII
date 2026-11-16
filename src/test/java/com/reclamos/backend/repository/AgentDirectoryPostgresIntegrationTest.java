package com.reclamos.backend.repository;

import com.reclamos.backend.dto.response.AgentDirectoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @ActiveProfiles("dev") @AutoConfigureMockMvc @Transactional
class AgentDirectoryPostgresIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired AgentDirectoryRepository repository;
    @Autowired MockMvc mvc;

    final String marker = "AGD-" + UUID.randomUUID();
    final Instant monthStart = Instant.parse("2026-11-01T03:00:00Z");
    final Instant monthEnd = Instant.parse("2026-12-01T03:00:00Z");
    final Instant inMonth = Instant.parse("2026-11-10T12:00:00Z");
    final Instant lastMonth = Instant.parse("2026-10-20T12:00:00Z");
    Long requestType;
    int ticketCount;

    void update(String sql, Object... values) {
        db.update(sql, Arrays.stream(values).map(value -> value instanceof Instant instant
                ? Timestamp.from(instant) : value).toArray());
    }

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        Long category = db.queryForObject("INSERT INTO categories(name,description,active,created_at,updated_at) VALUES (?,?,'true',now(),now()) RETURNING id", Long.class, marker, marker);
        Long sub = db.queryForObject("INSERT INTO subcategories(category_id,name,description,active) VALUES (?,?,?,true) RETURNING id", Long.class, category, marker, marker);
        requestType = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) VALUES (?,?,?,?,'COMPLAINT','M2','LOW','LOW',0,true,false,true) RETURNING id", Long.class, marker, sub, marker, marker);
    }

    Long staff(UUID actorId, String first, String last, String role, String area, boolean active) {
        return db.queryForObject("""
                INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,email,role,area_id,active,
                  last_synced_at,created_at,updated_at)
                VALUES (?,'EMPLEADO','MOCK',NULL,?,?,?,?,?,?,now(),now(),now()) RETURNING id
                """, Long.class, actorId, first, last, first.toLowerCase() + "@" + marker + ".test", role, area, active);
    }

    UUID ticket(Long agent, String status, UUID mainTicket) {
        UUID id = UUID.randomUUID();
        int index = ++ticketCount;
        update("""
                INSERT INTO tickets(id,public_id,tracking_code_hash,is_anonymous,anonymous_access_password_hash,request_type_id,ticket_type,
                  responsible_area_id,summary,description,form_data,current_status,current_priority,status_changed_at,created_at,updated_at,
                  is_escalated,reopen_count,is_public,estimated_affected_count,assigned_agent_id,main_ticket_id)
                VALUES (?,?,?,true,'test-hash',?,'COMPLAINT','M2','Ticket','Detalle','{}'::jsonb,?,'LOW',?,?,?,false,0,false,0,?,?)
                """, id, marker + "-" + index, marker + "-hash-" + index, requestType, status,
                inMonth, lastMonth, inMonth, agent, mainTicket);
        return id;
    }

    void resolution(UUID ticket, int number, Instant resolvedAt) {
        update("""
                INSERT INTO ticket_resolutions(id,ticket_id,resolution_number,resolution_type,public_message,resolved_by_type,
                  resolved_by_id,resolved_by_module_id,resolved_at)
                VALUES (?,?,?,'ACTION_COMPLETED','Listo','AGENT','agent','M2',?)
                """, UUID.randomUUID(), ticket, number, resolvedAt);
    }

    void slaCycle(UUID ticket, int cycle, String status, boolean completed) {
        update("""
                INSERT INTO ticket_sla(ticket_id,sla_type,cycle_number,policy_id,started_at,near_due_at,due_at,
                  status,completed_at,paused_at,total_paused_seconds)
                SELECT ?,'RESOLUTION',?,id,?::timestamptz,?::timestamptz,?::timestamptz,?,?::timestamptz,NULL,0
                FROM sla_policies WHERE sla_type='RESOLUTION' ORDER BY id LIMIT 1
                """, ticket, cycle, lastMonth, lastMonth.plusSeconds(3600), lastMonth.plusSeconds(7200), status,
                completed ? inMonth : null);
    }

    Map<UUID, AgentDirectoryEntry> directory() {
        return repository.list(monthStart, monthEnd).stream()
                .collect(Collectors.toMap(AgentDirectoryEntry::actorId, Function.identity()));
    }

    @Test
    void countsWorkloadResolutionsAndLatestClosedSlaPerStaffMember() {
        UUID agentActor = UUID.randomUUID();
        Long agent = staff(agentActor, "Laura", "Martinez", "AGENT", null, true);

        ticket(agent, "IN_PROGRESS", null);
        UUID waiting = ticket(agent, "PENDING_INFORMATION", null);
        ticket(agent, "DUPLICATE", waiting);                 // un duplicado no suma carga
        ticket(agent, "REGISTERED", null);                   // todavía no está activo

        UUID resolvedNow = ticket(agent, "RESOLVED", null);
        resolution(resolvedNow, 1, lastMonth);
        resolution(resolvedNow, 2, inMonth);                 // cuenta la última resolución, una sola vez
        slaCycle(resolvedNow, 1, "MET", true);

        UUID resolvedBefore = ticket(agent, "CLOSED", null);
        resolution(resolvedBefore, 1, lastMonth);            // fuera del mes
        slaCycle(resolvedBefore, 1, "MET", true);
        slaCycle(resolvedBefore, 2, "BREACHED", true);       // vale el último ciclo cerrado
        slaCycle(resolvedBefore, 3, "RUNNING", false);       // un ciclo abierto no cuenta

        AgentDirectoryEntry entry = directory().get(agentActor);

        assertThat(entry.firstName()).isEqualTo("Laura");
        assertThat(entry.role()).isEqualTo("AGENT");
        assertThat(entry.active()).isTrue();
        assertThat(entry.activeTickets()).isEqualTo(2);
        assertThat(entry.resolvedThisMonth()).isEqualTo(1);
        assertThat(entry.slaMet()).isEqualTo(1);
        assertThat(entry.slaBreached()).isEqualTo(1);
    }

    @Test
    void listsOnlyStaffWithZerosWhenTheyHaveNoTicketsAndActivePeopleFirst() {
        UUID inactiveActor = UUID.randomUUID();
        UUID responsibleActor = UUID.randomUUID();
        staff(inactiveActor, "Aaron", "Aaa", "AGENT", null, false);
        staff(responsibleActor, "Zoe", "Zzz", "AREA_RESPONSIBLE", "M3", true);
        UUID citizen = UUID.randomUUID();
        update("""
                INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,active,
                  last_synced_at,created_at,updated_at)
                VALUES (?,'CIUDADANO','MOCK',?,'Vecino','Comun','CITIZEN',true,now(),now(),now())
                """, citizen, citizen);

        List<AgentDirectoryEntry> all = repository.list(monthStart, monthEnd);
        List<UUID> ids = all.stream().map(AgentDirectoryEntry::actorId).toList();

        assertThat(ids).doesNotContain(citizen);
        assertThat(ids.indexOf(responsibleActor)).isLessThan(ids.indexOf(inactiveActor));
        AgentDirectoryEntry responsible = directory().get(responsibleActor);
        assertThat(responsible.areaId()).isEqualTo("M3");
        assertThat(responsible.activeTickets()).isZero();
        assertThat(responsible.resolvedThisMonth()).isZero();
        assertThat(responsible.slaMet() + responsible.slaBreached()).isZero();
        assertThat(all).allSatisfy(entry -> assertThat(entry.role()).isIn("AGENT", "AREA_RESPONSIBLE", "ADMIN"));
    }

    @Test
    void theEndpointServesTheDirectoryToAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/agents").with(authentication(auth("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/admin/agents").with(authentication(auth("AGENT"))))
                .andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken("staff", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
