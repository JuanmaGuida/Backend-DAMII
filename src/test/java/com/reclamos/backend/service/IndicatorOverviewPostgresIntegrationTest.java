package com.reclamos.backend.service;

import com.reclamos.backend.dto.IndicatorOverviewFilter;
import com.reclamos.backend.dto.response.IndicatorOverviewResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.IndicatorOverviewRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("dev") @AutoConfigureMockMvc @Transactional
class IndicatorOverviewPostgresIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired IndicatorOverviewRepository repository;
    @Autowired IndicatorOverviewService service;
    @Autowired MockMvc mvc;
    final String marker = "H24-" + UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-21T12:00:00Z");
    Long type;
    Long category;
    final String area = "M9";

    void update(String sql, Object... values) {
        db.update(sql, Arrays.stream(values).map(value -> value instanceof Instant instant
                ? java.sql.Timestamp.from(instant) : value).toArray());
    }

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()",String.class)).startsWith("PostgreSQL");
        category = db.queryForObject("INSERT INTO categories(name,description,active,created_at,updated_at) VALUES (?,?,'true',now(),now()) RETURNING id",Long.class,marker,marker);
        Long sub = db.queryForObject("INSERT INTO subcategories(category_id,name,description,active) VALUES (?,?,?,true) RETURNING id",Long.class,category,marker,marker);
        type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) VALUES (?,?,?,?,'COMPLAINT','M9','LOW','LOW',0,true,false,true) RETURNING id",Long.class,marker,sub,marker,marker);
    }
    IndicatorOverviewFilter filter(Integer days, String sla) {
        return new IndicatorOverviewFilter(category,null,null,area,null,false,sla,days);
    }
    IndicatorOverviewResponse overview() { return repository.overview(filter(null,null),now); }
    void insert(int count) {
        update("""
                INSERT INTO tickets(id,public_id,tracking_code_hash,is_anonymous,anonymous_access_password_hash,request_type_id,ticket_type,
                  responsible_area_id,summary,description,form_data,current_status,current_priority,
                  status_changed_at,created_at,updated_at,is_escalated,reopen_count,is_public,estimated_affected_count)
                SELECT gen_random_uuid(),?||'-'||i,?||'-hash-'||i,true,'test-hash',?,'COMPLAINT',?,'Ticket '||i,'Detalle',
                  '{}'::jsonb,'IN_PROGRESS','LOW',?::timestamptz,?::timestamptz,?::timestamptz,false,0,false,0
                FROM generate_series(1,?) i
                """,marker,marker,type,area,now.minusSeconds(3600),now.minusSeconds(86400),now.minusSeconds(3600),count);
    }
    UUID id(int index) { return db.queryForObject("SELECT id FROM tickets WHERE public_id=?",UUID.class,marker+"-"+index); }
    void cycle(UUID ticket,int cycle,String status,boolean complete,boolean paused) {
        update("""
                INSERT INTO ticket_sla(ticket_id,sla_type,cycle_number,policy_id,started_at,near_due_at,due_at,
                  status,completed_at,paused_at,total_paused_seconds)
                SELECT ?,'RESOLUTION',?,id,?::timestamptz,?::timestamptz,?::timestamptz,?,?::timestamptz,?::timestamptz,0
                FROM sla_policies WHERE sla_type='RESOLUTION' ORDER BY id LIMIT 1
                """,ticket,cycle,now.minusSeconds(86400),now.minusSeconds(3600),now.plusSeconds(3600),status,
                complete ? now.minusSeconds(1000) : null,paused ? now.minusSeconds(1800) : null);
    }

    @ParameterizedTest @ValueSource(ints={0,1,100,125})
    void globalCountsNeverDependOnTheFirstHundred(int count) {
        insert(count);
        if (count>100) {
            update("UPDATE tickets SET current_priority='CRITICAL',updated_at=created_at WHERE id=?",id(count));
            cycle(id(count),1,"BREACHED",false,false);
        }
        long tickets = db.queryForObject("SELECT count(*) FROM tickets",Long.class);
        long activities = db.queryForObject("SELECT count(*) FROM ticket_activities",Long.class);
        var response=overview();
        assertThat(response.counts().total()).isEqualTo(count);
        assertThat(response.counts().active()).isEqualTo(count);
        assertThat(response.statuses().stream().mapToLong(v->v.count()).sum()).isEqualTo(count);
        assertThat(response.counts().overdue()).isEqualTo(count>100?1:0);
        assertThat(response.counts().urgent()).isEqualTo(count>100?1:0);
        if(count>100) assertThat(response.attention()).extracting(v->v.id()).contains(id(count));
        assertThat(db.queryForObject("SELECT count(*) FROM tickets",Long.class)).isEqualTo(tickets);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities",Long.class)).isEqualTo(activities);
        var output = new ByteArrayOutputStream();
        repository.export(filter(null,null),now,output);
        assertThat(output.toString(StandardCharsets.UTF_8).lines().count()).isEqualTo(count+1L);
    }

    @Test void activeTerminalDuplicatePausedAndHistoricalCyclesAreSeparate() {
        insert(10);
        for(int i=1;i<=9;i++) {
            String state=List.of("REGISTERED","IN_REVIEW","ROUTED","IN_PROGRESS","PENDING_INFORMATION","RESOLVED","CLOSED","CANCELLED","DUPLICATE").get(i-1);
            update("UPDATE tickets SET current_status=? WHERE id=?",state,id(i));
        }
        update("UPDATE tickets SET main_ticket_id=? WHERE id=?",id(4),id(9));
        cycle(id(1),1,"BREACHED",true,false); cycle(id(1),2,"RUNNING",false,false);
        cycle(id(2),1,"NEAR_DUE",false,false);
        cycle(id(3),1,"BREACHED",false,false);
        cycle(id(4),1,"RUNNING",false,false);
        cycle(id(5),1,"NEAR_DUE",false,true);
        cycle(id(6),1,"MET",true,false); cycle(id(7),1,"BREACHED",true,false);
        cycle(id(8),1,"STOPPED",true,false); cycle(id(9),1,"BREACHED",true,false);
        update("UPDATE tickets SET current_priority='HIGH' WHERE id=?",id(10));
        var counts=overview().counts();
        assertThat(counts.active()).isEqualTo(6);
        assertThat(counts.overdue()).isEqualTo(1);
        assertThat(counts.nearDue()).isEqualTo(1);
        assertThat(counts.urgent()).isEqualTo(2); // HIGH alone is neither SLA breach nor critical urgency.
        assertThat(counts.completedSlaMeasured()).isEqualTo(3); // historical completed cycle of reopened #1 is evaluable.
        assertThat(counts.completedSlaMet()).isEqualTo(1);
        assertThat(counts.resolved()).isEqualTo(2);
        update("UPDATE ticket_sla SET completed_at=?::timestamptz WHERE ticket_id=?",now,id(3));
        update("UPDATE tickets SET current_status='RESOLVED' WHERE id=?",id(3));
        assertThat(overview().counts().overdue()).isZero();
        assertThat(overview().counts().active()).isEqualTo(5);
        assertThat(overview().attention()).hasSize(1);
    }

    @Test void combinedFiltersAndDatesApplyToAllAggregationsAndExport() {
        insert(4);
        UUID neighborhood=db.queryForObject("SELECT id FROM neighborhood ORDER BY id LIMIT 1",UUID.class);
        Long category=db.queryForObject("SELECT s.category_id FROM request_types rt JOIN subcategories s ON s.id=rt.subcategory_id WHERE rt.id=?",Long.class,type);
        update("INSERT INTO ticket_locations(ticket_id,neighborhood_id,created_at,updated_at) VALUES (?,?,now(),now())",id(1),neighborhood);
        update("INSERT INTO ticket_locations(ticket_id,address_line,created_at,updated_at) VALUES (?,'Calle 123',now(),now())",id(2));
        update("UPDATE tickets SET current_priority='HIGH' WHERE id=?",id(1));
        cycle(id(1),1,"BREACHED",false,false);
        var filters=new IndicatorOverviewFilter(category,neighborhood,Priority.HIGH,area,TicketStatus.IN_PROGRESS,false,"breached",30);
        var result=repository.overview(filters,now);
        assertThat(result.counts().total()).isEqualTo(1);
        assertThat(result.categories()).singleElement().satisfies(v->assertThat(v.count()).isEqualTo(1));
        assertThat(result.neighborhoods()).singleElement().satisfies(v->assertThat(v.key()).isEqualTo(neighborhood.toString()));
        assertThat(result.trend().stream().mapToLong(v->v.created()).sum()).isEqualTo(1);
        var output=new ByteArrayOutputStream(); repository.export(filters,now,output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains(marker+"-1").doesNotContain(marker+"-2");
        var without=new IndicatorOverviewFilter(category,null,null,area,null,true,null,null);
        assertThat(repository.overview(without,now).counts().total()).isEqualTo(2);
        update("UPDATE tickets SET created_at=?::timestamptz WHERE id=?",now.minusSeconds(40*86400L),id(4));
        var period=repository.overview(filter(30,null),now);
        assertThat(period.counts().total()).isEqualTo(3);
        assertThat(period.counts().previousTotal()).isEqualTo(1);
        assertThat(period.trend().stream().mapToLong(v->v.created()).sum()).isEqualTo(3);
    }

    @Test void slaFiltersAndBoundedAttentionUseCurrentFacts() {
        insert(15);
        cycle(id(1),1,"RUNNING",false,true);
        cycle(id(2),1,"NEAR_DUE",false,false);
        cycle(id(3),1,"BREACHED",false,false);
        assertThat(repository.overview(filter(null,"on_track"),now).counts().total()).isEqualTo(1);
        assertThat(repository.overview(filter(null,"near_due"),now).counts().total()).isEqualTo(1);
        assertThat(repository.overview(filter(null,"breached"),now).counts().total()).isEqualTo(1);
        assertThat(repository.overview(filter(null,"no_resolution_sla"),now).counts().total()).isEqualTo(12);
        update("UPDATE tickets SET current_priority='CRITICAL' WHERE request_type_id=?",type);
        assertThat(overview().attention()).hasSize(6);
        assertThat(overview().counts().urgent()).isEqualTo(15);
    }

    @Test void resolutionTimesComeFromResolutionRecordsAndCsvQuotesAndNeutralizesFormulaCells() {
        insert(2);
        update("UPDATE tickets SET current_status='CLOSED',status_changed_at=?::timestamptz,public_id=? WHERE id=?",now,"=1+1",id(1));
        UUID ticket=db.queryForObject("SELECT id FROM tickets WHERE request_type_id=? AND public_id='=1+1'",UUID.class,type);
        update("INSERT INTO ticket_resolutions(id,ticket_id,resolution_type,public_message,resolved_by_type,resolved_by_id,resolved_by_module_id,resolved_at,resolution_number) VALUES (?,?,'ACTION_COMPLETED','Listo','SYSTEM','test','M2',?::timestamptz,1)",UUID.randomUUID(),ticket,now.minusSeconds(20*3600));
        var result=repository.overview(filter(30,null),now);
        assertThat(result.counts().averageResolutionHours()).isEqualTo(4.0);
        assertThat(result.trend().stream().mapToLong(v->v.resolved()).sum()).isEqualTo(1);
        UUID neighborhood=UUID.randomUUID();
        update("INSERT INTO neighborhood(id,name,population) VALUES (?,'  @SUM(1)',0)",neighborhood);
        update("INSERT INTO ticket_locations(ticket_id,neighborhood_id,created_at,updated_at) VALUES (?,?,now(),now())",ticket,neighborhood);
        var exportFilter=filter(null,null);
        var output=new ByteArrayOutputStream(); repository.export(exportFilter,now,output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("\"'=1+1\"", "\"'  @SUM(1)\"", "\"Cerrado\"");
    }

    @ParameterizedTest @ValueSource(strings={"AGENT","ADMIN"})
    void authorizedRolesReadOverviewAndExportHeaders(String role) throws Exception {
        insert(1);
        mvc.perform(get("/api/indicators/overview").param("responsibleAreaId",area).param("categoryId",category.toString()).with(authentication(auth(role))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.counts.total").value(1)).andExpect(jsonPath("$.counts.active").value(1))
                .andExpect(jsonPath("$.attention").isArray()).andExpect(jsonPath("$.citizenId").doesNotExist());
        mvc.perform(get("/api/indicators/export").param("categoryId",category.toString()).with(authentication(auth(role))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Disposition","attachment; filename=metricas.csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(marker+"-1")));
        assertThat(service.overview(filter(null,null)).counts().total()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"CITIZEN","AREA_RESPONSIBLE",""})
    void rejectsUnauthorizedRolesForBothEndpoints(String role) throws Exception {
        for(String endpoint:List.of("overview","export")) {
            var request=get("/api/indicators/"+endpoint);
            if(!role.isEmpty()) request.with(authentication(auth(role)));
            mvc.perform(request).andExpect(role.isEmpty()?status().isUnauthorized():status().isForbidden());
        }
    }
    @Test void validatesFiltersWithoutStartingExport() throws Exception {
        for(String endpoint:List.of("overview","export")) {
            mvc.perform(get("/api/indicators/"+endpoint).param("days","500").with(authentication(auth("AGENT"))))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/indicators/"+endpoint).param("sla","invented").with(authentication(auth("AGENT"))))
                    .andExpect(status().isBadRequest());
        }
    }
    private UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken("staff",null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
}
