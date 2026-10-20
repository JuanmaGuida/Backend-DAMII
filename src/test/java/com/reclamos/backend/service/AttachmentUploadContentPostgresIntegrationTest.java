package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controllers, validation, local storage, PostgreSQL, Flyway and JPA; transactions roll back fixtures. */
@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AttachmentUploadContentPostgresIntegrationTest {
    private static final Path ROOT = temporaryRoot();
    private static final String PASSWORD = "h31-anonymous-password";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate db;
    @Autowired private InformationRequestService information;
    @Autowired private TrackingCodeService tracking;
    @Autowired private jakarta.persistence.EntityManager entities;
    private final ObjectMapper json = new ObjectMapper();
    private UUID owner;
    private long requestType;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("attachment.storage.root", () -> ROOT.toString());
    }

    @BeforeEach
    void fixture() {
        owner = UUID.randomUUID();
        db.update("INSERT INTO module_users (citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?, 'H31', 'Owner', 'CITIZEN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", owner);
        requestType = db.queryForObject("SELECT id FROM request_types WHERE code='INFORMAR_UN_BACHE'", Long.class);
        // A valid optional-evidence request, isolated by the test transaction.
        db.update("UPDATE request_types SET requires_location=false,allows_anonymous=true,base_risk='LOW',"
                + "minimum_priority='LOW' WHERE id=?", requestType);
        db.update("UPDATE form_templates SET active=false WHERE request_type_id=?", requestType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf"})
    void initialUploadStoresValidContentAndDownloadStillEnforcesOwner(String mime) throws Exception {
        byte[] bytes = AttachmentFixtures.bytes(mime);
        String response = mvc.perform(as(multipart("/api/tickets")
                        .file(data(createData(false)))
                        .file(new MockMultipartFile("evidence", "evidence.bin", mime, bytes)), owner, ModuleRole.CITIZEN))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID ticket = UUID.fromString(json.readTree(response).get("ticketId").asText());
        Long id = db.queryForObject("SELECT id FROM attachments WHERE ticket_id=?", Long.class, ticket);
        String key = db.queryForObject("SELECT storage_key FROM attachments WHERE id=?", String.class, id);
        assertArrayEquals(bytes, Files.readAllBytes(ROOT.resolve(key)));
        assertEquals(mime, db.queryForObject("SELECT content_type FROM attachments WHERE id=?", String.class, id));
        assertEquals("PUBLIC", db.queryForObject("SELECT visibility FROM attachments WHERE id=?", String.class, id));
        mvc.perform(get("/api/attachments/{id}/content", id).with(authentication(auth(owner, ModuleRole.CITIZEN))))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/attachments/{id}/content", id)
                        .with(authentication(auth(UUID.randomUUID(), ModuleRole.CITIZEN))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"initial,false", "initial,true", "later,false", "later,true", "answer,false", "answer,true"})
    void invalidContentIsRejectedBeforeAnyPersistenceOrReferences(String flow, boolean anonymous) throws Exception {
        UUID ticket = flow.equals("initial") ? null : ticket(anonymous);
        if (flow.equals("answer")) {
            db.update("UPDATE tickets SET current_status='IN_REVIEW' WHERE id=?", ticket);
            information.requestInformation(ticket, new CreateInformationRequest("Adjunte evidencia", null),
                    identity(UUID.randomUUID(), ModuleRole.AGENT));
            entities.flush();
        }
        long tickets = count("tickets");
        long attachments = count("attachments");
        long references = count("information_request_attachments");
        long events = count("outbox_events");
        long activities = count("ticket_activities");
        long files = storedFiles();
        String part = flow.equals("initial") ? "evidence" : "attachments";
        String payload = flow.equals("answer") ? "{\"responseMessage\":\"Prueba\"}" : "{\"visibility\":\"PUBLIC\"}";
        MockMultipartHttpServletRequestBuilder request;
        if (flow.equals("initial")) {
            request = multipart("/api/tickets").file(data(createData(anonymous)));
        } else if (anonymous) {
            String tracking = ticket.toString().replace("-", "");
            String credentials = "{\"trackingCode\":\"" + tracking + "\",\"anonymousAccessPassword\":\""
                    + PASSWORD + "\",\"payload\":" + payload + "}";
            request = multipart("/api/tracking/actions/" + (flow.equals("answer") ? "information-response" : "attachments"))
                    .file(data(credentials));
        } else {
            request = multipart("/api/tickets/" + ticket + "/" + (flow.equals("answer") ? "information-response" : "attachments"))
                    .file(data(payload));
        }
        request.file(new MockMultipartFile(part, "fake.png", "image/png", new byte[12]));
        if (!anonymous) as(request, owner, ModuleRole.CITIZEN);
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ATTACHMENT"))
                .andExpect(jsonPath("message").value("El contenido del archivo no corresponde al formato declarado o está incompleto."));
        assertEquals(tickets, count("tickets"));
        assertEquals(attachments, count("attachments"));
        assertEquals(references, count("information_request_attachments"));
        assertEquals(events, count("outbox_events"));
        assertEquals(activities, count("ticket_activities"));
        assertEquals(files, storedFiles());
        if (flow.equals("answer")) {
            assertEquals("PENDING_INFORMATION", db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticket));
            assertEquals("PENDING", db.queryForObject("SELECT status FROM information_requests WHERE ticket_id=?", String.class, ticket));
        }
    }

    @Test
    void staffInternalUploadKeepsVisibilityAndDownloadAuthorization() throws Exception {
        UUID ticket = ticket(false);
        UUID agent = UUID.randomUUID();
        String response = mvc.perform(as(multipart("/api/tickets/" + ticket + "/attachments")
                        .file(data("{\"visibility\":\"INTERNAL\"}"))
                        .file(AttachmentFixtures.file("attachments", "internal.pdf", "application/pdf")), agent, ModuleRole.AGENT))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).get(0).get("id").asLong();
        assertEquals("INTERNAL", db.queryForObject("SELECT visibility FROM attachments WHERE id=?", String.class, id));
        mvc.perform(get("/api/attachments/{id}/content", id).with(authentication(auth(owner, ModuleRole.CITIZEN))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/{id}/content", id).with(authentication(auth(agent, ModuleRole.AGENT))))
                .andExpect(status().isOk()).andExpect(content().bytes(AttachmentFixtures.bytes("application/pdf")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validLaterUploadAndInformationAnswerKeepReferencesAndAnonymousDownload(boolean anonymous) throws Exception {
        UUID ticket = ticket(anonymous);
        String credentials = "{\"trackingCode\":\"" + ticket.toString().replace("-", "")
                + "\",\"anonymousAccessPassword\":\"" + PASSWORD + "\"";
        String uploadData = "{\"visibility\":\"PUBLIC\"}";
        var later = multipart(anonymous ? "/api/tracking/actions/attachments" : "/api/tickets/" + ticket + "/attachments")
                .file(data(anonymous ? credentials + ",\"payload\":" + uploadData + "}" : uploadData))
                .file(AttachmentFixtures.file("attachments", "later.webp", "image/webp"));
        if (!anonymous) as(later, owner, ModuleRole.CITIZEN);
        String uploaded = mvc.perform(later).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(uploaded).get(0).get("id").asLong();
        if (anonymous) {
            mvc.perform(post("/api/tracking/actions/attachments/{id}/content", id).contentType("application/json")
                            .content(credentials + "}"))
                    .andExpect(status().isOk()).andExpect(content().bytes(AttachmentFixtures.bytes("image/webp")));
        }
        entities.flush();
        db.update("UPDATE tickets SET current_status='IN_REVIEW' WHERE id=?", ticket);
        entities.clear();
        information.requestInformation(ticket, new CreateInformationRequest("Adjunte evidencia", null),
                identity(UUID.randomUUID(), ModuleRole.AGENT));
        entities.flush();
        String answerData = "{\"responseMessage\":\"Evidencia\"}";
        var answer = multipart(anonymous ? "/api/tracking/actions/information-response" : "/api/tickets/" + ticket + "/information-response")
                .file(data(anonymous ? credentials + ",\"payload\":" + answerData + "}" : answerData))
                .file(AttachmentFixtures.file("attachments", "answer.heic", "image/heif"));
        if (!anonymous) as(answer, owner, ModuleRole.CITIZEN);
        mvc.perform(answer).andExpect(status().isOk());
        entities.flush();
        assertEquals("ANSWERED", db.queryForObject("SELECT status FROM information_requests WHERE ticket_id=?", String.class, ticket));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM information_request_attachments r JOIN attachments a "
                + "ON a.id=r.attachment_id WHERE a.ticket_id=?", Integer.class, ticket));
    }

    @Test
    void databaseRollbackRemovesAlreadyStoredBytesAndMetadata() throws Exception {
        long before = storedFiles();
        UUID ticket = ticket(false);
        mvc.perform(as(multipart("/api/tickets/" + ticket + "/attachments")
                        .file(data("{\"visibility\":\"PUBLIC\"}"))
                        .file(AttachmentFixtures.file("attachments", "proof.png", "image/png")), owner, ModuleRole.CITIZEN))
                .andExpect(status().isCreated());
        assertEquals(before + 1, storedFiles());
        org.springframework.test.context.transaction.TestTransaction.flagForRollback();
        org.springframework.test.context.transaction.TestTransaction.end();
        assertEquals(before, storedFiles());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM attachments WHERE ticket_id=?", Integer.class, ticket));
    }

    private UUID ticket(boolean anonymous) {
        UUID id = UUID.randomUUID();
        var credential = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD);
        db.update("INSERT INTO tickets (id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,"
                        + "request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,"
                        + "is_escalated,status_changed_at,created_at,updated_at) "
                        + "SELECT ?, ?, ?, ?, ?, ?, id,ticket_type,'M6','H31','H31','{}'::jsonb,'REGISTERED','LOW',"
                        + "false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM request_types WHERE id=?",
                id, "H31-" + id.toString().substring(0, 20), tracking.hash(id.toString().replace("-", "")),
                anonymous ? null : owner, anonymous, anonymous ? credential : null, requestType);
        return id;
    }

    private String createData(boolean anonymous) {
        return "{\"requestTypeId\":" + requestType + ",\"summary\":\"H31\",\"description\":\"H31\",\"formData\":{}"
                + (anonymous ? ",\"anonymousAccessPassword\":\"" + PASSWORD + "\"" : "") + "}";
    }

    private MockMultipartFile data(String value) {
        return new MockMultipartFile("data", "", "application/json", value.getBytes(StandardCharsets.UTF_8));
    }

    private long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private long storedFiles() throws IOException {
        try (var files = Files.walk(ROOT)) { return files.filter(Files::isRegularFile).count(); }
    }

    private static MockMultipartHttpServletRequestBuilder as(MockMultipartHttpServletRequestBuilder request, UUID id, ModuleRole role) {
        request.with(authentication(auth(id, role)));
        return request;
    }

    private static UsernamePasswordAuthenticationToken auth(UUID id, ModuleRole role) {
        return new UsernamePasswordAuthenticationToken(identity(id, role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    private static AuthenticatedIdentity identity(UUID id, ModuleRole role) {
        return new AuthenticatedIdentity("h31", id, "H31", null, role);
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("m2-h31-attachments-"); }
        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
}
