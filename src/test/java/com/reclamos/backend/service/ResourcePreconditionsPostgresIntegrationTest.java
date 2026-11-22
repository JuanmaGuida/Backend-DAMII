package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.show-sql=false",
        "ticket.priority.scan-delay=3600000", "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc
class ResourcePreconditionsPostgresIntegrationTest {
    @Autowired TicketService service;
    @Autowired TicketMessageService messages;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired com.reclamos.backend.storage.FileStorage storage;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID(), agent = UUID.randomUUID();
    final String marker = "E4-02-" + UUID.randomUUID();
    Long category, subcategory, type;
    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID citizen : List.of(owner, stranger, agent)) {
            db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                    + "VALUES (?, 'CIUDADANO', 'MOCK', ?,'E4','Test',?,TRUE,now(),now(),now())", citizen, citizen, citizen.equals(agent) ? "AGENT" : "CITIZEN");
        }
        category = db.queryForObject("INSERT INTO categories(name,description,active,created_at,updated_at) VALUES (?,?,true,now(),now()) RETURNING id", Long.class, marker, marker);
        subcategory = db.queryForObject("INSERT INTO subcategories(category_id,name,description,active) VALUES (?,?,?,true) RETURNING id", Long.class, category, marker, marker);
        type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "VALUES (?,?,?,?,'COMPLAINT','M9','LOW','LOW',0,true,false,true) RETURNING id", Long.class, marker, subcategory, marker, marker);
    }

    @AfterEach void cleanup() {
        for (UUID id : db.queryForList("SELECT id FROM tickets WHERE request_type_id=?", UUID.class, type)) {
            for (String key : db.queryForList("SELECT storage_key FROM attachments WHERE ticket_id=?", String.class, id))
                storage.delete(key);
            for (String table : List.of("ticket_creation_idempotency", "ticket_messages", "notification_logs", "outbox_events",
                    "ticket_activities", "ticket_sla", "ticket_locations", "attachments"))
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        db.update("DELETE FROM request_types WHERE id=?", type);
        db.update("DELETE FROM subcategories WHERE id=?", subcategory);
        db.update("DELETE FROM categories WHERE id=?", category);
        db.update("DELETE FROM module_users WHERE citizen_id IN (?,?,?)", owner, stranger, agent);
    }


    @Test void classificationGetConditionalWriteAndStaleRejectionAreAtomic() throws Exception {
        UUID id = ticket(); String path = classification(id);
        String old = etag(path, agent);
        assertThat(etag(path, agent)).isEqualTo(old).startsWith("\"classification-");
        String next = mvc.perform(patch(path).with(authentication(auth(agent)))
                .header("If-Match", old).contentType("application/json").content(classificationBody(type)))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        assertThat(next).isNotEqualTo(old).isEqualTo(etag(path, agent));
        var before = snapshot(id);
        mvc.perform(patch(path).with(authentication(auth(agent))).header("If-Match", old)
                .contentType("application/json").content(classificationBody(type)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(412))
                .andExpect(jsonPath("$.instance").value(path))
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        assertThat(snapshot(id)).isEqualTo(before);
        // A functional change retains its existing conflict, and rolls back the revision too.
        mvc.perform(patch(path).with(authentication(auth(agent))).header("If-Match", next)
                .contentType("application/json").content(classificationBody(type + 1)))
                .andExpect(status().isConflict());
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test void messageGetEditNewRevisionAndStaleRejectionHaveNoSideEffects() throws Exception {
        UUID id = ticket(); Long message = message(id, owner, MessageVisibility.PUBLIC);
        String path = messagePath(id, message); String old = etag(path, owner);
        String next = mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", old)
                .contentType("application/json").content("{\"text\":\"editado\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("editado"))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(next).isNotEqualTo(old).isEqualTo(etag(path, owner));
        var before = snapshot(id);
        mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", old)
                .contentType("application/json").content("{\"text\":\"rechazado\"}"))
                .andExpect(status().isPreconditionFailed());
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test void concurrentClassificationRequestsConsumeSameRevisionAtMostOnce() throws Exception {
        UUID id = ticket(); String path = classification(id); String old = etag(path, agent);
        race(path, agent, old, classificationBody(type));
        assertThat(etag(path, agent)).isNotEqualTo(old);
    }

    @Test void concurrentMessageEditsConsumeSameRevisionAtMostOnce() throws Exception {
        UUID id = ticket(); String path = messagePath(id, message(id, owner, MessageVisibility.PUBLIC));
        String old = etag(path, owner);
        race(path, owner, old, "{\"text\":\"concurrente\"}");
        assertThat(etag(path, owner)).isNotEqualTo(old);
    }

    @Test void missingHeaderRetainsCompatibilityAndUnconditionalWritesInvalidateOldTags() throws Exception {
        UUID id = ticket(); String path = classification(id); String old = etag(path, agent);
        mvc.perform(patch(path).with(authentication(auth(agent)))
                .contentType("application/json").content(classificationBody(type))).andExpect(status().isOk());
        assertThat(etag(path, agent)).isEqualTo(old); // Existing compatibility no-op.
        String chat = messagePath(id, message(id, owner, MessageVisibility.PUBLIC)); String before = etag(chat, owner);
        mvc.perform(patch(chat).with(authentication(auth(owner)))
                .contentType("application/json").content("{\"text\":\"sin encabezado\"}")).andExpect(status().isOk());
        assertThat(etag(chat, owner)).isNotEqualTo(before);
        mvc.perform(delete(chat).with(authentication(auth(owner)))).andExpect(status().isNoContent());
    }

    @Test void authorizationAndPrivateMessageBoundariesArePreservedEvenWithStaleTags() throws Exception {
        UUID id = ticket(); Long publicMessage = message(id, owner, MessageVisibility.PUBLIC);
        String path = messagePath(id, publicMessage); var before = snapshot(id);
        mvc.perform(patch(path).with(authentication(auth(stranger))).header("If-Match", "\"old\"")
                .contentType("application/json").content("{\"text\":\"hack\"}")).andExpect(status().isForbidden());
        mvc.perform(patch(classification(id)).with(authentication(auth(owner))).header("If-Match", "\"old\"")
                .contentType("application/json").content(classificationBody(type))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(authentication(auth(stranger)))).andExpect(status().isForbidden());
        mvc.perform(get(classification(id)).with(authentication(auth(owner)))).andExpect(status().isForbidden());
        assertThat(snapshot(id)).isEqualTo(before);
        Long internal = message(id, agent, MessageVisibility.INTERNAL);
        mvc.perform(get(messagePath(id, internal)).with(authentication(auth(owner)))).andExpect(status().isNotFound());
        etag(messagePath(id, internal), agent);
        db.update("UPDATE ticket_messages SET source_module_id='M3' WHERE id=?", publicMessage);
        mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", "*")
                .contentType("application/json").content("{\"text\":\"hack\"}")).andExpect(status().isForbidden());
    }

    @Test void weakTagsFailListsAndWildcardWorkAndMalformedHeaderDoesNotWrite() throws Exception {
        UUID id = ticket(); String path = messagePath(id, message(id, owner, MessageVisibility.PUBLIC));
        String tag = etag(path, owner); var before = snapshot(id);
        mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", "W/" + tag)
                .contentType("application/json").content("{\"text\":\"texto\"}")).andExpect(status().isPreconditionFailed());
        mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", tag + ",broken")
                .contentType("application/json").content("{\"text\":\"texto\"}")).andExpect(status().isBadRequest());
        assertThat(snapshot(id)).isEqualTo(before);
        mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", "\"old\"", tag)
                .contentType("application/json").content("{\"text\":\"inicial\"}")).andExpect(status().isOk());
        assertThat(etag(path, owner)).isNotEqualTo(tag); // Same text still consumes a conditional revision.
        mvc.perform(patch(path).with(authentication(auth(owner))).header("If-Match", "*")
                .contentType("application/json").content("{\"text\":\"comodín\"}")).andExpect(status().isOk());
    }

    @Test void areaAuthorLosesReadAndEditAccessAfterReassignment() throws Exception {
        UUID id = ticket();
        var area = new AuthenticatedIdentity(stranger.toString(), stranger, "Área", "M9", ModuleRole.AREA_RESPONSIBLE);
        var request = new TicketMessageRequest(); request.setText("del área"); request.setVisibility(MessageVisibility.INTERNAL);
        Long message = messages.create(id, request, area).id();
        var authentication = new UsernamePasswordAuthenticationToken(area, null,
                List.of(new SimpleGrantedAuthority("ROLE_AREA_RESPONSIBLE")));
        String path = messagePath(id, message);
        String tag = mvc.perform(get(path).with(authentication(authentication))).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        db.update("UPDATE tickets SET responsible_area_id='M8' WHERE id=?", id);
        var before = snapshot(id);
        mvc.perform(get(path).with(authentication(authentication))).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(authentication(authentication)).header("If-Match", tag)
                .contentType("application/json").content("{\"text\":\"sin acceso\"}")).andExpect(status().isForbidden());
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test void databaseRevisionTracksOtherTicketWriters() throws Exception {
        UUID id = ticket(); String path = classification(id); String old = etag(path, agent);
        db.update("UPDATE tickets SET summary='Otra escritura' WHERE id=?", id);
        assertThat(etag(path, agent)).isNotEqualTo(old);
        mvc.perform(patch(path).with(authentication(auth(agent))).header("If-Match", old)
                .contentType("application/json").content(classificationBody(type))).andExpect(status().isPreconditionFailed());
    }

    UUID ticket() {
        UUID id = service.create(new CreateTicketRequest(type, "ETag", "Prueba", Map.of(), null), identity(owner), null).ticketId();
        service.startReview(id, identity(agent));
        return id;
    }
    Long message(UUID id, UUID author, MessageVisibility visibility) {
        var request = new TicketMessageRequest(); request.setText("inicial"); request.setVisibility(visibility);
        return messages.create(id, request, identity(author)).id();
    }
    String classification(UUID id) { return "/api/tickets/" + id + "/classification"; }
    String classificationBody(Long id) { return "{\"requestTypeId\":" + id + "}"; }
    String messagePath(UUID id, Long message) { return "/api/tickets/" + id + "/messages/" + message; }
    AuthenticatedIdentity identity(UUID id) {
        return new AuthenticatedIdentity(id.toString(), id, "E4 Test", "M9", id.equals(agent) ? ModuleRole.AGENT : ModuleRole.CITIZEN);
    }
    UsernamePasswordAuthenticationToken auth(UUID id) {
        return new UsernamePasswordAuthenticationToken(identity(id), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + identity(id).role())));
    }
    String etag(String path, UUID actor) throws Exception {
        return mvc.perform(get(path).with(authentication(auth(actor)))).andExpect(status().isOk())
                .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");
    }
    List<Object> snapshot(UUID id) {
        List<Object> result = new ArrayList<>();
        for (String table : List.of("tickets", "ticket_messages", "ticket_activities", "outbox_events", "notification_logs"))
            result.add(db.queryForList("SELECT row_to_json(t)::text FROM " + table + " t WHERE "
                    + (table.equals("tickets") ? "id" : "ticket_id") + "=? ORDER BY id", String.class, id));
        return result;
    }
    void race(String path, UUID actor, String tag, String body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<Integer> write = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(patch(path).with(authentication(auth(actor))).header("If-Match", tag)
                        .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(write); var second = pool.submit(write);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 412);
        } finally { pool.shutdownNow(); }
    }
}
