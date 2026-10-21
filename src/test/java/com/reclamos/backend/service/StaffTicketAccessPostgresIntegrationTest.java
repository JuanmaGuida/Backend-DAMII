package com.reclamos.backend.service;

import com.reclamos.backend.dto.*;
import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("dev") @AutoConfigureMockMvc @Transactional
class StaffTicketAccessPostgresIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired TicketMessageService messages;
    @Autowired TicketReclassificationService reclassification;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    final UUID owner = UUID.randomUUID(), staff = UUID.randomUUID();
    final String marker = "H21-" + UUID.randomUUID();
    Long type;

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID citizen : List.of(owner, staff)) {
            db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) VALUES (?,'H21','Test','CITIZEN',TRUE,now(),now(),now())", citizen);
        }
        type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) SELECT ?,id,?,?,'REQUEST','M6','LOW','LOW',0,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id",
                Long.class, marker, marker, marker);
    }

    UUID create(String area, UUID citizen, CreateTicketRequest.LocationData location) {
        UUID id = tickets.create(new CreateTicketRequest(type, marker, "Detalle", Map.of(), location),
                identity(citizen, ModuleRole.CITIZEN, null), null).ticketId();
        em.flush(); db.update("UPDATE tickets SET responsible_area_id=? WHERE id=?", area, id); em.clear();
        return id;
    }
    AuthenticatedIdentity identity(UUID id, ModuleRole role, String area) {
        return new AuthenticatedIdentity(id.toString(), id, "H21", area, role);
    }
    UsernamePasswordAuthenticationToken auth(UUID id, ModuleRole role, String area) {
        return new UsernamePasswordAuthenticationToken(identity(id, role, area), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    TicketFilter filter(Boolean noLocation) { return new TicketFilter(null,null,null,"M9",null,null,marker,noLocation); }

    @ParameterizedTest @EnumSource(value=ModuleRole.class, names={"AGENT","ADMIN","AREA_RESPONSIBLE"})
    void authorizedStaffReadsAndCitizenProjectionDoesNotGrow(ModuleRole role) throws Exception {
        UUID id = create("M6", owner, new CreateTicketRequest.LocationData("Av. Siempre 123", "Siempre", "123", null,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE, "Portón"));
        db.update("UPDATE tickets SET current_progress=0,form_data='{" + "\"answer\":false,\"zero\":0,\"unknown\":null}'::jsonb WHERE id=?",id);
        em.clear();
        mvc.perform(get("/api/staff/tickets/"+id).with(authentication(auth(staff,role,"M6"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formData.answer").value(false))
                .andExpect(jsonPath("$.formData.zero").value(0)).andExpect(jsonPath("$.formData.unknown").isEmpty())
                .andExpect(jsonPath("$.currentProgress").value(0)).andExpect(jsonPath("$.location.addressLine").value("Av. Siempre 123"))
                .andExpect(jsonPath("$.location.reference").value("Portón")).andExpect(jsonPath("$.trackingCodeHash").doesNotExist());
        mvc.perform(get("/api/staff/tickets/"+id+"/citizen-view").with(authentication(auth(staff,role,"M6"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formData").doesNotExist())
                .andExpect(jsonPath("$.formTemplate").doesNotExist()).andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.currentProgress").doesNotExist());
        mvc.perform(get("/api/tickets/"+id).with(authentication(auth(owner,ModuleRole.CITIZEN,null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formData").doesNotExist());
    }

    @Test void historicalVersionIsUsedAndRiskConfigurationIsExcluded() throws Exception {
        Long historical = db.queryForObject("INSERT INTO form_templates(request_type_id,version,active) VALUES (?,1,FALSE) RETURNING id",Long.class,type);
        Long active = db.queryForObject("INSERT INTO form_templates(request_type_id,version,active) VALUES (?,2,TRUE) RETURNING id",Long.class,type);
        db.update("INSERT INTO form_fields(form_template_id,code,label,type,required,allow_unknown,display_order,config) VALUES (?,'old','Pregunta histórica','SELECT',FALSE,FALSE,1,'{\"options\":[{\"value\":\"a\",\"label\":\"Antes\"}],\"private\":\"hidden\"}')",historical);
        db.update("INSERT INTO form_fields(form_template_id,code,label,type,required,allow_unknown,display_order,config) VALUES (?,'new','Pregunta nueva','TEXT',FALSE,FALSE,1,'{}')",active);
        // Create without requiring an answer, then pin the historical confirmed classification.
        UUID id=create("M6",owner,null);
        db.update("UPDATE tickets SET form_template_id=?,form_data='{\"old\":\"a\"}' WHERE id=?",historical,id);em.clear();
        mvc.perform(get("/api/staff/tickets/"+id).with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formTemplate.id").value(historical))
                .andExpect(jsonPath("$.formTemplate.version").value(1))
                .andExpect(jsonPath("$.formTemplate.fields[0].label").value("Pregunta histórica"))
                .andExpect(jsonPath("$.formTemplate.fields[0].options[0].label").value("Antes"))
                .andExpect(jsonPath("$.formTemplate.fields[0].config").doesNotExist())
                .andExpect(jsonPath("$.formTemplate.fields[0].riskRules").doesNotExist());
    }

    @Test void areaListingScopesBeforePaginationAndIgnoresManipulatedArea() throws Exception {
        List<UUID> permitted = List.of(create("M6",owner,null),create("M6",owner,null),create("M6",staff,null));
        create("M9",owner,null); create("M9",staff,null);
        Set<String> seen = new HashSet<>();
        for(int page=0;page<3;page++) {
            var result=mvc.perform(get("/api/staff/tickets/area").param("search",marker).param("responsibleAreaId","M9")
                    .param("page",String.valueOf(page)).param("size","1").param("sort","id,asc")
                    .with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.content[0].responsibleAreaId").value("M6"))
                    .andReturn();
            seen.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).at("/content/0/id").asText());
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(permitted.stream().map(UUID::toString).toList());
        mvc.perform(get("/api/tickets").with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff/tickets/area").param("sort","trackingCodeHash,asc")
                .with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> tickets.listAreaTickets(filter(false),PageRequest.of(0,201),identity(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))
                .isInstanceOf(com.reclamos.backend.exception.InvalidTicketRequestException.class);
    }

    @ParameterizedTest @ValueSource(strings={"address","street","neighborhood","coordinates","empty","none"})
    void partialLocationAndFilterHaveSameMeaning(String kind) throws Exception {
        UUID neighborhood=kind.equals("neighborhood") ? db.queryForObject("SELECT id FROM neighborhood LIMIT 1",UUID.class):null;
        var location=kind.equals("none") ? null : new CreateTicketRequest.LocationData(kind.equals("address")?"Dirección sin barrio":null,
                kind.equals("street")?"Calle":null,null,neighborhood,kind.equals("coordinates")?java.math.BigDecimal.ZERO:null,
                kind.equals("coordinates")?java.math.BigDecimal.ZERO:null,kind.equals("empty")?"Referencia sola":null);
        UUID id=create("M6",owner,location);
        boolean useful=!Set.of("empty","none").contains(kind);
        var page=tickets.listAreaTickets(filter(true),PageRequest.of(0,20),identity(staff,ModuleRole.AREA_RESPONSIBLE,"M6"));
        assertThat(page.getTotalElements()).isEqualTo(useful?0:1);
        assertThat(tickets.listAreaTickets(filter(false),PageRequest.of(0,20),identity(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))
                .getContent().getFirst().isHasLocation()).isEqualTo(useful);
        var detail=tickets.getStaffDetail(id,identity(staff,ModuleRole.AREA_RESPONSIBLE,"M6"));
        assertThat(detail.getCurrentProgress()).isNull();assertThat(detail.getFormTemplate()).isNull();
        assertThat(detail.getLocation()).isEqualTo(location==null?null:new com.reclamos.backend.dto.response.StaffTicketLocationResponse(
                location.addressLine(),location.street(),location.streetNumber(),neighborhood,
                neighborhood==null?null:db.queryForObject("SELECT name FROM neighborhood WHERE id=?",String.class,neighborhood),
                location.latitude()==null?null:location.latitude().setScale(6),location.longitude()==null?null:location.longitude().setScale(6),location.reference()));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"M9"," "})
    void missingOrForeignAreaDeniedWithoutWriting(String area) throws Exception {
        UUID id=create("M6",owner,null);
        var actor=auth(staff,ModuleRole.AREA_RESPONSIBLE,area);
        mvc.perform(get("/api/staff/tickets/"+id).with(authentication(actor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff/tickets/"+id+"/citizen-view").with(authentication(actor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/"+id+"/messages").with(authentication(actor))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/"+id+"/messages").with(authentication(actor)).contentType("application/json")
                .content("{\"visibility\":\"INTERNAL\",\"text\":\"No escribir\"}")).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id=?",Integer.class,id)).isZero();
    }

    @ParameterizedTest @EnumSource(value=ModuleRole.class,names={"CITIZEN","AGENT","ADMIN","AREA_RESPONSIBLE"})
    void areaListingEntryGateAndOwnReadOnly(ModuleRole role) throws Exception {
        UUID id=create("M9",staff,null);
        mvc.perform(get("/api/staff/tickets/area").with(authentication(auth(staff,role,"M6"))))
                .andExpect(role==ModuleRole.AREA_RESPONSIBLE?status().isOk():status().isForbidden());
        mvc.perform(get("/api/staff/tickets/"+id).with(authentication(auth(staff,role,"M6"))))
                .andExpect(role==ModuleRole.CITIZEN?status().isForbidden():status().isOk());
        mvc.perform(post("/api/tickets/"+id+"/review").with(authentication(auth(staff,role,"M6"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/"+id+"/messages").with(authentication(auth(staff,role,"M6")))
                .contentType("application/json").content("{\"visibility\":\"INTERNAL\",\"text\":\"Propio\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/"+id+"/messages").with(authentication(auth(staff,role,"M6")))
                .contentType("application/json").content("{\"visibility\":\"PUBLIC\",\"text\":\"Como ciudadano\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authorType").value("CITIZEN"));
    }

    @Test void areaPublicInternalMessagesPersistActorAndStayPrivate() throws Exception {
        UUID id=create("M6",owner,null);
        for(String visibility:List.of("PUBLIC","INTERNAL"))
            mvc.perform(post("/api/tickets/"+id+"/messages").with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))
                    .contentType("application/json").content("{\"visibility\":\""+visibility+"\",\"text\":\"Mensaje "+visibility+"\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.authorType").value("AREA_RESPONSIBLE"))
                    .andExpect(jsonPath("$.authorId").value(staff.toString()));
        assertThat(db.queryForList("SELECT actor_type FROM ticket_activities WHERE ticket_id=? AND action_type IN ('PUBLIC_MESSAGE_SENT','INTERNAL_MESSAGE_ADDED')",String.class,id))
                .containsExactly("AREA_RESPONSIBLE","AREA_RESPONSIBLE");
        mvc.perform(get("/api/tickets/"+id+"/messages").with(authentication(auth(owner,ModuleRole.CITIZEN,null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].visibility").value("PUBLIC"));
        mvc.perform(get("/api/tickets/"+id+"/messages").with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        for(String suffix:List.of("review","route","simulate-status-update"))
            mvc.perform(post("/api/tickets/"+id+"/"+suffix).with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/"+id+"/resolution").with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))
                .contentType("application/json").content("{\"type\":\"ACTION_COMPLETED\",\"publicMessage\":\"No autorizado\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void unauthenticatedCitizenAndManipulatedId() throws Exception {
        UUID id=create("M6",owner,null);
        mvc.perform(get("/api/staff/tickets/area")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/staff/tickets/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/staff/tickets/"+id).with(authentication(auth(owner,ModuleRole.CITIZEN,null)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff/tickets/"+UUID.randomUUID()).with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))))
                .andExpect(status().isNotFound());
    }

    @Test void provisionalAnswersStayInExplicitProposalAndOwnerFlows() throws Exception {
        UUID id = create("M6", owner, null);
        db.update("UPDATE module_users SET role='AGENT' WHERE citizen_id=?", staff);
        var agent = identity(staff, ModuleRole.AGENT, null);
        tickets.startReview(id, agent);
        Long target = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) SELECT ?,id,?,?,'REQUEST','M6','LOW','LOW',0,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id",
                Long.class, marker+"-new", marker+"-new", marker);
        Long template = db.queryForObject("INSERT INTO form_templates(request_type_id,version,active) VALUES (?,1,TRUE) RETURNING id",Long.class,target);
        for(String code:List.of("known","missing"))
            db.update("INSERT INTO form_fields(form_template_id,code,label,type,required,allow_unknown,display_order,config) VALUES (?,?,?,'TEXT',TRUE,FALSE,1,'{}')",template,code,code);
        var proposal = reclassification.propose(id, new ProposeReclassificationRequest(target, Map.of("known","Private provisional"),"Complete"), agent);
        for(ModuleRole role:List.of(ModuleRole.AGENT,ModuleRole.ADMIN,ModuleRole.AREA_RESPONSIBLE)) {
            var actor = auth(staff,role,"M6");
            mvc.perform(get("/api/staff/tickets/"+id).with(authentication(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.formData.known").doesNotExist())
                    .andExpect(jsonPath("$.pendingInformationRequest.reclassification").isEmpty());
            mvc.perform(get("/api/staff/tickets/"+id+"/citizen-view").with(authentication(actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.pendingInformationRequest.reclassification").isEmpty());
        }
        mvc.perform(get("/api/tickets/"+id).with(authentication(auth(owner,ModuleRole.CITIZEN,null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingInformationRequest.reclassification.availableAnswers.known").value("Private provisional"));
        mvc.perform(get("/api/staff/tickets/"+id+"/reclassification-proposals/"+proposal.id())
                        .with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest @EnumSource(MessageVisibility.class)
    void areaCannotEditOrDeleteOwnMessagesAfterTicketMoves(MessageVisibility visibility) throws Exception {
        UUID id = create("M6", owner, null);
        var request = new TicketMessageRequest(); request.setVisibility(visibility); request.setText("Original");
        var message = messages.create(id, request, identity(staff, ModuleRole.AREA_RESPONSIBLE, "M6"));
        mvc.perform(patch("/api/tickets/"+id+"/messages/"+message.id())
                        .with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))
                        .contentType("application/json").content("{\"text\":\"Edición autorizada\"}"))
                .andExpect(status().isOk());
        em.flush(); db.update("UPDATE tickets SET responsible_area_id='M9' WHERE id=?", id); em.clear();
        mvc.perform(patch("/api/tickets/"+id+"/messages/"+message.id())
                        .with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6")))
                        .contentType("application/json").content("{\"text\":\"No autorizado\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/tickets/"+id+"/messages/"+message.id())
                        .with(authentication(auth(staff,ModuleRole.AREA_RESPONSIBLE,"M6"))))
                .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT text FROM ticket_messages WHERE id=?",String.class,message.id()))
                .isEqualTo("Edición autorizada");
    }
}



