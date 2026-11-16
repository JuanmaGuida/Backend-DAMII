package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.IdempotencyConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.ModuleRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000", "notification.pending-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc
class TicketCreationIdempotencyPostgresIntegrationTest {
    @Autowired TicketService service;
    @Autowired AnonymousTicketAccessService anonymousAccess;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired com.reclamos.backend.storage.FileStorage storage;
    @Autowired TicketCreationIdempotencyService idempotency;
    @Autowired com.reclamos.backend.repository.TicketRepository tickets;
    @Autowired TrackingCodeCipher cipher;
    @Autowired org.springframework.core.env.Environment environment;
    @MockitoSpyBean NotificationService notifications;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    final String marker = "E4-01-" + UUID.randomUUID();
    Long category, subcategory, type;

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID citizen : List.of(owner, stranger)) {
            db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                    + "VALUES (?, 'CIUDADANO', 'MOCK', ?,'E4','Test','CITIZEN',TRUE,now(),now(),now())", citizen, citizen);
        }
        category = db.queryForObject("INSERT INTO categories(name,description,active,created_at,updated_at) VALUES (?,?,true,now(),now()) RETURNING id", Long.class, marker, marker);
        subcategory = db.queryForObject("INSERT INTO subcategories(category_id,name,description,active) VALUES (?,?,?,true) RETURNING id", Long.class, category, marker, marker);
        type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "VALUES (?,?,?,?,'COMPLAINT','M9','LOW','LOW',0,true,false,true) RETURNING id", Long.class, marker, subcategory, marker, marker);
    }

    @AfterEach void cleanup() {
        reset(notifications);
        for (UUID id : db.queryForList("SELECT id FROM tickets WHERE request_type_id=?", UUID.class, type)) {
            for (String key : db.queryForList("SELECT storage_key FROM attachments WHERE ticket_id=?", String.class, id))
                storage.delete(key);
            for (String table : List.of("ticket_creation_idempotency", "notification_logs", "outbox_events",
                    "ticket_activities", "ticket_sla", "ticket_locations", "attachments"))
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        db.update("DELETE FROM request_types WHERE id=?", type);
        db.update("DELETE FROM subcategories WHERE id=?", subcategory);
        db.update("DELETE FROM categories WHERE id=?", category);
        db.update("DELETE FROM module_users WHERE citizen_id IN (?,?)", owner, stranger);
    }

    @Test void identicalRetryPreservesOriginalResponseAndEveryFunctionalEffectAcrossServiceRecreation() {
        var first = create(identity(owner), "same");
        var before = effects(first.ticketId());
        assertThat(before).containsExactly(1L, 1L, 2L, 1L, 0L);
        // Change catalog after creation: retry must not validate/recreate against today's catalog.
        db.update("UPDATE request_types SET active=false WHERE id=?", type);
        assertThat(create(identity(owner), "same")).isEqualTo(first);
        var restarted = new TicketCreationIdempotencyService(db, mapper, tickets, cipher,
                environment.getProperty("app.tracking.encryption.key-base64"));
        var replay = transactions.execute(status -> restarted.replay(
                restarted.reserve("same", identity(owner), request(), List.of()), identity(owner)));
        assertThat(replay).isEqualTo(first);
        assertThat(effects(first.ticketId())).isEqualTo(before);
        assertThat(ticketCount()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_creation_idempotency WHERE ticket_id=?", Long.class, first.ticketId())).isEqualTo(1);
        verify(notifications, times(1)).queue(any(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentRequestsHaveOneResourceAndOneSetOfEffects(boolean anonymous) throws Exception {
        var identity = anonymous ? null : identity(owner);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<CreateTicketResponse> task = () -> {
                ready.countDown();
                assertThat(start.await(10, SECONDS)).isTrue();
                return create(identity, "concurrent");
            };
            var one = executor.submit(task);
            var two = executor.submit(task);
            assertThat(ready.await(10, SECONDS)).isTrue();
            start.countDown();
            var responses = List.of(one.get(20, SECONDS), two.get(20, SECONDS));
            UUID id = responses.getFirst().ticketId();
            assertThat(responses).allSatisfy(response -> assertThat(response.ticketId()).isEqualTo(id));
            assertThat(ticketCount()).isEqualTo(1);
            assertThat(effects(id)).containsExactly(anonymous ? 0L : 1L, 1L, 2L, anonymous ? 0L : 1L, 0L);
            if (anonymous) {
                assertThat(responses.stream().filter(r -> r.trackingCode() != null).count()).isEqualTo(1);
                assertThat(responses.stream().filter(r -> r.generatedAnonymousAccessPassword() != null).count()).isEqualTo(1);
            } else assertThat(responses.getFirst()).isEqualTo(responses.getLast());
        }
    }

    @Test void differentPayloadConflictsWithoutChangingOriginalAndOtherCitizensHaveIndependentScopes() {
        var first = create(identity(owner), "shared");
        var before = effects(first.ticketId());
        assertThatThrownBy(() -> service.create(new CreateTicketRequest(type, "changed", marker, Map.of(), null), identity(owner), null, "shared"))
                .isInstanceOf(IdempotencyConflictException.class);
        var other = create(identity(stranger), "shared");
        assertThat(other.ticketId()).isNotEqualTo(first.ticketId());
        assertThat(other.trackingCode()).isNotEqualTo(first.trackingCode());
        assertThat(create(identity(stranger), "shared")).isEqualTo(other);
        assertThatThrownBy(() -> service.getById(first.ticketId(), identity(stranger)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThat(effects(first.ticketId())).isEqualTo(before);
        assertThat(ticketCount()).isEqualTo(2);
    }

    @Test void lateCreationFailureRollsBackReservationTicketAndAllEffectsAndAllowsRetry() {
        long reservations = db.queryForObject("SELECT count(*) FROM ticket_creation_idempotency", Long.class);
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("E4 simulated failure after all business effects");
        }).when(notifications).queue(any(), any());
        assertThatThrownBy(() -> create(identity(owner), "rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(ticketCount()).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_creation_idempotency", Long.class)).isEqualTo(reservations);
        for (String table : List.of("outbox_events", "ticket_activities", "ticket_sla", "notification_logs")) {
            assertThat(db.queryForObject("SELECT count(*) FROM " + table + " e JOIN tickets t ON e.ticket_id=t.id WHERE t.request_type_id=?", Long.class, type)).isZero();
        }
        reset(notifications);
        var retried = create(identity(owner), "rollback");
        assertThat(effects(retried.ticketId())).containsExactly(1L, 1L, 2L, 1L, 0L);
    }

    @Test void concurrentWaiterTakesOverWhenFirstTransactionRollsBack() throws Exception {
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiterStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var failed = executor.submit(() -> transactions.executeWithoutResult(status -> {
                create(identity(owner), "takeover");
                created.countDown();
                await(release);
                throw new IllegalStateException("rollback first creator");
            }));
            assertThat(created.await(10, SECONDS)).isTrue();
            var waiter = executor.submit(() -> {
                waiterStarted.countDown();
                return create(identity(owner), "takeover");
            });
            try {
                assertThat(waiterStarted.await(10, SECONDS)).isTrue();
                assertThatThrownBy(() -> waiter.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> failed.get(10, SECONDS)).isInstanceOf(ExecutionException.class);
            var result = waiter.get(20, SECONDS);
            assertThat(ticketCount()).isEqualTo(1);
            assertThat(create(identity(owner), "takeover")).isEqualTo(result);
            assertThat(effects(result.ticketId())).containsExactly(1L, 1L, 2L, 1L, 0L);
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anonymousSecretsRemainOneTimeAndOriginalCredentialsStillWork(boolean chosenPassword) {
        var request = new CreateTicketRequest(type, marker, marker, Map.of(), null,
                chosenPassword ? "chosen secret password" : null, null);
        var first = service.create(request, null, null, "anon");
        var before = db.queryForMap("SELECT * FROM tickets WHERE id=?", first.ticketId());
        var replay = service.create(request, null, null, "anon");
        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThat(replay.publicId()).isEqualTo(first.publicId());
        assertThat(replay.trackingCode()).isNull();
        assertThat(replay.generatedAnonymousAccessPassword()).isNull();
        String password = chosenPassword ? "chosen secret password" : first.generatedAnonymousAccessPassword();
        assertThat(anonymousAccess.authenticate(first.trackingCode(), password).ticketId()).isEqualTo(first.ticketId());
        assertThat(first.generatedAnonymousAccessPassword()).satisfies(value -> {
            if (chosenPassword) assertThat(value).isNull(); else assertThat(value).isNotBlank();
        });
        assertThat(db.queryForMap("SELECT * FROM tickets WHERE id=?", first.ticketId()))
                .containsEntry("tracking_code_hash", before.get("tracking_code_hash"))
                .containsEntry("anonymous_access_password_hash", before.get("anonymous_access_password_hash"))
                .containsEntry("tracking_code_ciphertext", null);
        var persisted = db.queryForMap("SELECT * FROM ticket_creation_idempotency WHERE ticket_id=?", first.ticketId());
        assertThat(persisted.toString()).doesNotContain(password, first.trackingCode(), marker);
        assertThat(persisted.get("key_hash")).isNotEqualTo("anon");
        assertThatThrownBy(() -> service.create(new CreateTicketRequest(type, marker, marker, Map.of(), null,
                "different secret password", null), null, null, "anon")).isInstanceOf(IdempotencyConflictException.class);
        // Anonymous and authenticated callers never share a reservation.
        assertThat(create(identity(owner), "anon").ticketId()).isNotEqualTo(first.ticketId());
    }

    @Test void withoutHeaderEveryCallCreatesANewResourceAndNoIdempotencyRecord() {
        var one = service.create(request(), null, null);
        var two = service.create(request(), null, null);
        assertThat(one.ticketId()).isNotEqualTo(two.ticketId());
        assertThat(one.generatedAnonymousAccessPassword()).isNotBlank();
        assertThat(two.generatedAnonymousAccessPassword()).isNotBlank();
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_creation_idempotency WHERE ticket_id IN (?,?)", Long.class, one.ticketId(), two.ticketId())).isZero();
    }

    @Test void employeeCannotReserveOrReplayCitizenCreation() {
        create(identity(owner), "employee");
        var employee = new AuthenticatedIdentity("employee", owner, IdentityActorType.EMPLEADO, null,
                "employee", "M2", ModuleRole.ADMIN);
        assertThatThrownBy(() -> create(employee, "employee")).isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThat(ticketCount()).isEqualTo(1);
    }

    @Test void jsonAndMultipartUseSameKeyContractAndFileBytesParticipateInComparison() throws Exception {
        byte[] data = mapper.writeValueAsBytes(request());
        var first = mvc.perform(post("/api/tickets").with(authentication(auth(owner)))
                .header("Idempotency-Key", "http-json").contentType("application/json").content(data))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var original = mapper.readValue(first, CreateTicketResponse.class);
        mvc.perform(multipart("/api/tickets").file(new MockMultipartFile("data", "", "application/json", data))
                .with(authentication(auth(owner))).header("Idempotency-Key", "http-json"))
                .andExpect(status().isCreated()).andExpect(content().json(first));
        mvc.perform(post("/api/tickets").with(authentication(auth(owner))).header("Idempotency-Key", "http-json")
                .contentType("application/json").content(mapper.writeValueAsBytes(
                        new CreateTicketRequest(type, "different", marker, Map.of(), null))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        var file = image(0xff0000);
        var result = mvc.perform(multipart("/api/tickets").file(new MockMultipartFile("data", "", "application/json", data))
                .file(file).with(authentication(auth(owner))).header("Idempotency-Key", "http-files"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var withFile = mapper.readValue(result, CreateTicketResponse.class);
        mvc.perform(multipart("/api/tickets").file(new MockMultipartFile("data", "", "application/json", data))
                .file(file).with(authentication(auth(owner))).header("Idempotency-Key", "http-files"))
                .andExpect(status().isCreated()).andExpect(content().json(result));
        mvc.perform(multipart("/api/tickets").file(new MockMultipartFile("data", "", "application/json", data))
                .file(image(0x00ff00)).with(authentication(auth(owner))).header("Idempotency-Key", "http-files"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(effects(withFile.ticketId())).containsExactly(1L, 1L, 2L, 1L, 1L);
        assertThat(effects(original.ticketId())).containsExactly(1L, 1L, 2L, 1L, 0L);
        assertThat(ticketCount()).isEqualTo(2);
        mvc.perform(post("/api/tickets").header("Idempotency-Key", " ").contentType("application/json").content(data))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/tickets").header("Authorization", "Bearer invalid")
                .header("Idempotency-Key", "http-json").contentType("application/json").content(data))
                .andExpect(status().isUnauthorized());
    }

    @Test void anonymousHttpReplayDoesNotDeliverSecrets() throws Exception {
        byte[] body = mapper.writeValueAsBytes(request());
        var first = mvc.perform(post("/api/tickets").header("Idempotency-Key", "anon-http")
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var created = mapper.readValue(first, CreateTicketResponse.class);
        mvc.perform(post("/api/tickets").header("Idempotency-Key", "anon-http")
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ticketId").value(created.ticketId().toString()))
                .andExpect(jsonPath("$.trackingCode").isEmpty())
                .andExpect(jsonPath("$.generatedAnonymousAccessPassword").isEmpty());
    }

    @Test void canonicalJsonIgnoresObjectOrderAndNumericScaleButIncludesArraysAndContact() {
        var one = new LinkedHashMap<String, Object>();
        one.put("nested", Map.of("b", 2, "a", 1));
        one.put("array", List.of(1, 2));
        var two = new LinkedHashMap<String, Object>();
        two.put("array", List.of(new java.math.BigDecimal("1.00"), 2));
        two.put("nested", Map.of("a", 1.0, "b", 2));
        // Exercise only request comparison: dynamic form validation is independent of idempotency.
        transactions.executeWithoutResult(status -> {
            var claim = idempotency.reserve("canonical", identity(owner),
                    new CreateTicketRequest(type, marker, marker, one, null), List.of());
            var ticket = service.create(request(), identity(owner), null);
            idempotency.complete(claim, ticket.ticketId(), ticket.publicId());
        });
        transactions.executeWithoutResult(status -> assertThat(idempotency.reserve("canonical", identity(owner),
                new CreateTicketRequest(type, marker, marker, two, null), List.of()).ticketId()).isNotNull());
        two.put("array", List.of(2, 1));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> idempotency.reserve("canonical", identity(owner),
                new CreateTicketRequest(type, marker, marker, two, null), List.of())))
                .isInstanceOf(IdempotencyConflictException.class);
        var anonymous = new CreateTicketRequest(type, marker, marker, Map.of(), null, "secret password",
                new CreateTicketRequest.AnonymousContact(com.reclamos.backend.entity.AnonymousContactChannel.EMAIL, "test@example.com"));
        service.create(anonymous, null, null, "contact");
        assertThatThrownBy(() -> service.create(new CreateTicketRequest(type, marker, marker, Map.of(), null,
                "secret password", new CreateTicketRequest.AnonymousContact(com.reclamos.backend.entity.AnonymousContactChannel.EMAIL,
                "other@example.com")), null, null, "contact")).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test void concurrentDifferentPayloadHasOneWinnerAndOneControlledConflict() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (String summary : List.of("one", "two")) {
                futures.add(executor.submit(() -> {
                    await(start);
                    try {
                        return service.create(new CreateTicketRequest(type, summary, marker, Map.of(), null), identity(owner), null, "race-conflict");
                    } catch (IdempotencyConflictException conflict) { return conflict; }
                }));
            }
            start.countDown();
            var outcomes = List.of(futures.getFirst().get(20, SECONDS), futures.getLast().get(20, SECONDS));
            assertThat(outcomes.stream().filter(CreateTicketResponse.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(IdempotencyConflictException.class::isInstance).count()).isEqualTo(1);
            assertThat(ticketCount()).isEqualTo(1);
        } finally { start.countDown(); }
    }

    @Test void migrationUpgradesV51WithoutInventingHistoricalReservationsAndEnforcesUniqueness() {
        String schema = "e4_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var before = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration").schemas(schema).defaultSchema(schema).target("51").load();
            before.migrate();
            db.update("INSERT INTO " + schema + ".tickets (id,public_id,tracking_code_hash,is_anonymous,anonymous_access_password_hash,"
                    + "request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,"
                    + "status_changed_at,created_at,updated_at) SELECT ?,?,'historical-hash',true,'$2a$10$historical',id,ticket_type,"
                    + "responsible_area_id,'Historical','Historical','{}'::jsonb,'REGISTERED','LOW',now(),now(),now() FROM "
                    + schema + ".request_types ORDER BY id LIMIT 1", UUID.randomUUID(), "TK-2026-999999");
            var historical = db.queryForList("SELECT id FROM " + schema + ".tickets", UUID.class);
            assertThat(historical).hasSize(1);
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).target("52").load().migrate();
            assertThat(db.queryForList("SELECT id FROM " + schema + ".tickets", UUID.class)).isEqualTo(historical);
            assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket_creation_idempotency", Long.class)).isZero();
            String insert = "INSERT INTO " + schema + ".ticket_creation_idempotency(identity_scope,key_hash,request_hash) VALUES (?,?,?)";
            db.update(insert, "citizen:one", "a".repeat(64), "b".repeat(64));
            assertThatThrownBy(() -> db.update(insert, "citizen:one", "a".repeat(64), "c".repeat(64)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatCode(() -> db.update(insert, "citizen:two", "a".repeat(64), "b".repeat(64))).doesNotThrowAnyException();
            assertThatThrownBy(() -> db.update(insert, "citizen:three", "not-a-hash", "b".repeat(64)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE " + schema + ".ticket_creation_idempotency SET ticket_id=?", UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, SECONDS)).isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private MockMultipartFile image(int rgb) throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("evidence", "photo.png", "image/png", bytes.toByteArray());
    }
    private CreateTicketResponse create(AuthenticatedIdentity identity, String key) {
        return service.create(request(), identity, null, key);
    }
    private CreateTicketRequest request() { return new CreateTicketRequest(type, marker, marker, Map.of(), null); }
    private AuthenticatedIdentity identity(UUID citizen) { return new AuthenticatedIdentity("e4-01", citizen, "Test", null, ModuleRole.CITIZEN); }
    private UsernamePasswordAuthenticationToken auth(UUID citizen) {
        return new UsernamePasswordAuthenticationToken(identity(citizen), null, List.of(new SimpleGrantedAuthority("ROLE_CITIZEN")));
    }
    private long ticketCount() { return db.queryForObject("SELECT count(*) FROM tickets WHERE request_type_id=?", Long.class, type); }
    // Outbox, creation activity, SLA cycles, notifications and attachments.
    private List<Long> effects(UUID id) {
        return List.of("outbox_events", "ticket_activities", "ticket_sla", "notification_logs", "attachments").stream()
                .map(table -> db.queryForObject("SELECT count(*) FROM " + table + " WHERE ticket_id=?", Long.class, id)).toList();
    }
}
