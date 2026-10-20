package com.reclamos.backend.security;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Local acceptance checks: real controllers, services, security and PostgreSQL.
 * Only the authenticated principal is supplied by the test (M1 is out of scope).
 * Use ONLY an empty disposable database, with the explicit opt-in below.
 */
@SpringBootTest(properties = {
        "app.identity.mock.citizen-password=clase11-fictional-citizen",
        "app.identity.mock.agent-password=clase11-fictional-agent",
        "app.identity.mock.area-responsible-password=clase11-fictional-area",
        "app.identity.mock.admin-password=clase11-fictional-admin",
        "spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=86400000",
        "ticket.resolution.confirmation-scan-delay=86400000",
        "ticket.sla.milestone-scan-delay=86400000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="clase11.security.enabled", matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Clase11SecurityAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired CategoryRepository categories;
    @Autowired SubcategoryRepository subcategories;
    @Autowired RequestTypeRepository types;
    @Autowired ModuleUserRepository users;
    @Autowired TicketRepository tickets;
    private Long lowType, highType;
    private final AuthenticatedIdentity a = identity(1, ModuleRole.CITIZEN, null);
    private final AuthenticatedIdentity b = identity(2, ModuleRole.CITIZEN, null);
    private final AuthenticatedIdentity area = identity(3, ModuleRole.AREA_RESPONSIBLE, "M6");
    private final AuthenticatedIdentity admin = identity(4, ModuleRole.ADMIN, null);
    private Path evidence;

    static AuthenticatedIdentity identity(int n, ModuleRole role, String area) {
        return new AuthenticatedIdentity("clase11-" + n,
                UUID.fromString("c1100000-0000-0000-0000-" + String.format("%012d", n)),
                "Persona ficticia " + n, area, role);
    }

    @BeforeEach void fixtures() throws Exception {
        String url;
        try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
            url = connection.getMetaData().getURL();
        }
        assertTrue(url.contains("127.0.0.1:55439/clase11_security"), "Dedicated local database required");
        evidence = Path.of(System.getProperty("clase11.evidence.dir"));
        Files.createDirectories(evidence);
        for (var p : List.of(a,b,area,admin)) {
            if (users.findByCitizenId(p.citizenId()).isEmpty()) {
                ModuleUser u = new ModuleUser();
                u.setCitizenId(p.citizenId()); u.setFirstName("Persona"); u.setLastName("Ficticia");
                u.setRole(p.role()); u.setAreaId(p.areaId()); u.setLastSyncedAt(Instant.now());
                users.saveAndFlush(u);
            }
        }
        Category c = new Category(); c.setName("Clase11-" + UUID.randomUUID()); c.setDescription("Fixture académico");
        c = categories.saveAndFlush(c);
        Subcategory s = new Subcategory(); s.setCategory(c); s.setName("Seguridad local");
        s = subcategories.saveAndFlush(s);
        lowType = type(s, Risk.LOW); highType = type(s, Risk.HIGH);
    }

    Long type(Subcategory s, Risk risk) {
        RequestType t = new RequestType(); t.setCode("C11-" + UUID.randomUUID());
        t.setName("Caso ficticio " + risk); t.setDescription("Prueba de seguridad"); t.setSubcategory(s);
        t.setTicketType(TicketType.COMPLAINT); t.setResponsibleAreaId("M6");
        t.setMinimumPriority(Priority.LOW); t.setBaseRisk(risk);
        t.setAffectedPopulationFactor(BigDecimal.ZERO); t.setAllowsAnonymous(true); t.setRequiresLocation(false);
        return types.saveAndFlush(t).getId();
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, AuthenticatedIdentity p) {
        return req.with(authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())))));
    }
    String body(Long type, String extras) {
        return "{\"requestTypeId\":"+type+",\"summary\":\"Reclamo ficticio Clase 11\","
                +"\"description\":\"Datos exclusivamente de prueba\",\"formData\":{}"+extras+"}";
    }
    JsonNode create(AuthenticatedIdentity p, String id) throws Exception {
        var req = post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(lowType,""));
        return record(id, p == null ? "ANONYMOUS" : p.role().name(), p == null ? req : as(req,p),201);
    }
    JsonNode record(String id, String actor, MockHttpServletRequestBuilder req, int expected) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String text = "Actor: "+actor+"\n"+r.getRequest().getMethod()+" "+r.getRequest().getRequestURI()
                +"\nRequest: "+r.getRequest().getContentAsString()
                +"\nExpected HTTP: "+expected+"\nObserved HTTP: "+r.getResponse().getStatus()
                +"\nResponse: "+raw+"\n";
        Files.writeString(evidence.resolve(id+".txt"),text,StandardCharsets.UTF_8);
        System.out.println("EVIDENCE "+id+" expected="+expected+" observed="+r.getResponse().getStatus());
        assertEquals(expected,r.getResponse().getStatus(),text);
        return raw.isBlank() ? json.readTree("{}") : json.readTree(raw);
    }
    void note(String id, Object value) throws Exception {
        Files.writeString(evidence.resolve(id+".txt"), String.valueOf(value), StandardCharsets.UTF_8);
        System.out.println("EVIDENCE "+id+" "+value);
    }
    Map<String,Object> snapshot(UUID id) {
        return db.queryForMap("select current_status, status_changed_at, (select count(*) from ticket_messages where ticket_id=?) as messages, (select count(*) from ticket_activities where ticket_id=?) as activities from tickets where id=?", id,id,id);
    }

    @Test @Order(1) void sec01_bola() throws Exception {
        UUID id = UUID.fromString(create(a,"SEC01-create-A").get("ticketId").asText());
        Map<String,Object> before = snapshot(id);
        record("SEC01-read-B","CITIZEN B",as(get("/api/tickets/"+id),b),403);
        Map<String,String> actions = new LinkedHashMap<>();
        actions.put("messages","{\"visibility\":\"PUBLIC\",\"text\":\"Intento ajeno\"}");
        actions.put("information-response","{\"responseMessage\":\"Intento ajeno\"}");
        actions.put("resolution/reopen","{\"reason\":\"Intento ajeno\"}");
        actions.put("cancel","{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}");
        for (var action : actions.entrySet()) record("SEC01-"+action.getKey().replace('/','-')+"-B","CITIZEN B",
                as(post("/api/tickets/"+id+"/"+action.getKey()).contentType(MediaType.APPLICATION_JSON).content(action.getValue()),b),403);
        JsonNode owned = record("SEC01-read-A","CITIZEN A",as(get("/api/tickets/"+id),a),200);
        assertEquals(id.toString(), owned.get("id").asText());
        JsonNode list = record("SEC01-list-B","CITIZEN B",as(get("/api/me/tickets?size=100"),b),200);
        assertFalse(list.toString().contains(id.toString()));
        Map<String,Object> after = snapshot(id);
        assertEquals(before,after);
        note("SEC01-state", "Before="+before+"\nAfter="+after+"\nNo mutation; B listing excludes T: PASS");
    }

    @Test @Order(2) void sec02_bfla() throws Exception {
        UUID id = UUID.fromString(create(a,"SEC02-create-A").get("ticketId").asText());
        Map<String,Object> before = snapshot(id);
        record("SEC02-citizen-staff","CITIZEN",as(get("/api/staff/tickets/"+id),a),403);
        record("SEC02-area-cancel","AREA_RESPONSIBLE M6",as(post("/api/tickets/"+id+"/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}"),area),403);
        record("SEC02-area-information","AREA_RESPONSIBLE M6",as(post("/api/tickets/"+id+"/information-request").contentType(MediaType.APPLICATION_JSON).content("{\"messageForCitizen\":\"Dato ficticio\"}"),area),403);
        assertEquals(before,snapshot(id));
        db.update("update tickets set responsible_area_id='M7' where id=?",id);
        before = snapshot(id);
        record("SEC02-area-other-area","AREA_RESPONSIBLE M6",as(get("/api/staff/tickets/"+id),area),403);
        record("SEC02-area-other-message","AREA_RESPONSIBLE M6",as(post("/api/tickets/"+id+"/messages").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\",\"text\":\"Intento en otra área\"}"),area),403);
        assertEquals(before,snapshot(id));
        db.update("update tickets set responsible_area_id='M6' where id=?",id);
        record("SEC02-area-own-area","AREA_RESPONSIBLE M6",as(get("/api/staff/tickets/"+id),area),200);
        record("SEC02-area-public","AREA_RESPONSIBLE M6",as(post("/api/tickets/"+id+"/messages").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\",\"text\":\"Mensaje ficticio público\"}"),area),201);
        record("SEC02-area-internal","AREA_RESPONSIBLE M6",as(post("/api/tickets/"+id+"/messages").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"INTERNAL\",\"text\":\"Nota ficticia interna\"}"),area),201);
        UUID own = UUID.fromString(create(admin,"SEC02-create-admin").get("ticketId").asText());
        before = snapshot(own);
        record("SEC02-admin-own-information","ADMIN owner",as(post("/api/tickets/"+own+"/information-request").contentType(MediaType.APPLICATION_JSON).content("{\"messageForCitizen\":\"Dato ficticio\"}"),admin),403);
        record("SEC02-admin-own-internal","ADMIN owner",as(post("/api/tickets/"+own+"/messages").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"INTERNAL\",\"text\":\"Intento interno propio\"}"),admin),403);
        assertEquals(before,snapshot(own));
        note("SEC02-state","Rejected actions did not change status, status_changed_at, messages or activities: PASS; PUBLIC and INTERNAL controls persisted: PASS");
    }

    @Test @Order(3) void sec04_massAssignment() throws Exception {
        String extras = ",\"citizenId\":\""+b.citizenId()+"\",\"currentPriority\":\"CRITICAL\",\"riskScore\":100,\"risk\":\"CRITICAL\",\"currentStatus\":\"RESOLVED\",\"isEscalated\":true,\"escalated\":true,\"publicId\":\"FORGED-ID\",\"estimatedAffectedCount\":999999";
        JsonNode created = record("SEC04-forged-create","CITIZEN A",as(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(lowType,extras)),a),201);
        UUID id = UUID.fromString(created.get("ticketId").asText());
        Ticket saved = tickets.findById(id).orElseThrow();
        assertEquals(a.citizenId(),saved.getCitizenId()); assertEquals(TicketStatus.REGISTERED,saved.getCurrentStatus());
        assertEquals(Priority.LOW,saved.getCurrentPriority()); assertFalse(saved.isEscalated());
        assertNotEquals("FORGED-ID",saved.getPublicId()); assertEquals(0,saved.getEstimatedAffectedCount());
        note("SEC04-persistence", "citizenId="+saved.getCitizenId()+"\nstatus="+saved.getCurrentStatus()+"\npriority="+saved.getCurrentPriority()+"\nescalated="+saved.isEscalated()+"\npublicId="+saved.getPublicId()+"\nestimatedAffectedCount="+saved.getEstimatedAffectedCount());
        record("SEC04-read-A","CITIZEN A",as(get("/api/tickets/"+id),a),200);
        JsonNode bList = record("SEC04-list-B","CITIZEN B",as(get("/api/me/tickets?size=100"),b),200);
        assertFalse(bList.toString().contains(id.toString()));
        long before = tickets.count();
        JsonNode high = record("SEC04-high-no-evidence","CITIZEN A",as(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(highType,",\"risk\":\"LOW\",\"riskScore\":0")),a),422);
        assertEquals("EVIDENCE_REQUIRED", high.get("code").asText());
        assertEquals(before,tickets.count());
        note("SEC04-count","Tickets before HIGH request="+before+"\nTickets after HIGH request="+tickets.count()+"\nNo persistence: PASS; B listing excludes created ticket: PASS");
    }

    @Test @Order(4) void sec03_rateLimitObservation() throws Exception {
        JsonNode created = create(null,"SEC03-create-anonymous");
        String code = created.get("trackingCode").asText();
        Instant start = Instant.now();
        List<Integer> statuses = new ArrayList<>();
        for (int n=0;n<20;n++) {
            MvcResult r = mvc.perform(post("/api/tracking/access").with(req -> {req.setRemoteAddr("127.0.0.1"); return req;})
                    .contentType(MediaType.APPLICATION_JSON).content("{\"trackingCode\":\""+String.format("%032d",n)+"\"}")).andReturn();
            statuses.add(r.getResponse().getStatus());
            note("SEC03-attempt-"+String.format("%02d",n+1),"POST /api/tracking/access from 127.0.0.1\nHTTP "+r.getResponse().getStatus()+"\n"+r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        note("SEC03-rate-summary", "20 invalid requests; elapsed_ms="+java.time.Duration.between(start,Instant.now()).toMillis()+"; statuses="+statuses+"; any_429="+statuses.contains(429));
        record("SEC03-valid-after-attempts","ANONYMOUS",post("/api/tracking/access").contentType(MediaType.APPLICATION_JSON).content("{\"trackingCode\":\""+code+"\"}"),200);
        record("SEC03-write-without-password","ANONYMOUS",post("/api/tickets/"+created.get("ticketId").asText()+"/messages").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\",\"text\":\"Intento sin contraseña\",\"trackingCode\":\""+code+"\"}"),401);
        // This is an observation test, not an assertion that the security criterion passes.
    }

    @Test @Order(5) void sec06_disguisedFileObservation() throws Exception {
        UUID id = UUID.fromString(create(a,"SEC06-create-A").get("ticketId").asText());
        byte[] fake = "Plain text, not a JPEG. Fictional academic test.".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("attachments","prueba.jpg","image/jpeg",fake);
        MockMultipartFile data = new MockMultipartFile("data","","application/json","{\"visibility\":\"PUBLIC\"}".getBytes(StandardCharsets.UTF_8));
        MvcResult r = mvc.perform(multipart("/api/tickets/"+id+"/attachments").file(data).file(file)
                .with(authentication(new UsernamePasswordAuthenticationToken(a,null,List.of(new SimpleGrantedAuthority("ROLE_CITIZEN")))))).andReturn();
        note("SEC06-disguised-file", "POST /api/tickets/"+id+"/attachments\nMultipart: data={visibility:PUBLIC}; attachments filename=prueba.jpg; declared MIME=image/jpeg\nActual bytes (UTF-8): "+new String(fake,StandardCharsets.UTF_8)+"\nExpected security behavior: reject 400/415\nObserved HTTP="+r.getResponse().getStatus()+"\nResponse="+r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MockMultipartFile traversal = new MockMultipartFile("attachments","../../prueba.jpg","image/jpeg",fake);
        MvcResult rejected = mvc.perform(multipart("/api/tickets/"+id+"/attachments").file(data).file(traversal)
                .with(authentication(new UsernamePasswordAuthenticationToken(a,null,List.of(new SimpleGrantedAuthority("ROLE_CITIZEN")))))).andReturn();
        assertEquals(400,rejected.getResponse().getStatus());
        note("SEC06-traversal", "filename=../../prueba.jpg\nObserved HTTP="+rejected.getResponse().getStatus()+"\nResponse="+rejected.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
