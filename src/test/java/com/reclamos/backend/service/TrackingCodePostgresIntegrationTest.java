package com.reclamos.backend.service;

import com.reclamos.backend.dto.IndicatorOverviewFilter;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.exception.TrackingCodeUnavailableException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed service transactions against a disposable PostgreSQL, never a shared database. */
@SpringBootTest(properties = {"spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc
class TrackingCodePostgresIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired OwnerTrackingCodeService recovery;
    @Autowired TrackingCodeService codes;
    @Autowired TrackingService tracking;
    @Autowired AnonymousTicketAccessService anonymousAccess;
    @Autowired IndicatorOverviewService indicators;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.core.env.Environment environment;
    @MockitoSpyBean TrackingCodeCipher cipher;
    @MockitoSpyBean TicketOutboxService outbox;
    final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    final String marker = "TRACKING-" + UUID.randomUUID();
    Long category, subcategory, type;

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID citizen : List.of(owner, stranger)) {
            db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                    + "VALUES (?, 'CIUDADANO', 'MOCK', ?,'Tracking','Test','CITIZEN',TRUE,now(),now(),now())", citizen, citizen);
        }
        category = db.queryForObject("INSERT INTO categories(name,description,active,created_at,updated_at) VALUES (?,?,true,now(),now()) RETURNING id", Long.class, marker, marker);
        subcategory = db.queryForObject("INSERT INTO subcategories(category_id,name,description,active) VALUES (?,?,?,true) RETURNING id", Long.class, category, marker, marker);
        type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "VALUES (?,?,?,?,'COMPLAINT','M9','LOW','LOW',0,true,false,true) RETURNING id", Long.class, marker, subcategory, marker, marker);
    }

    @AfterEach void cleanup() {
        for (UUID id : db.queryForList("SELECT id FROM tickets WHERE request_type_id=?", UUID.class, type)) {
            for (String table : List.of("notification_logs", "outbox_events", "ticket_activities", "ticket_sla", "ticket_locations"))
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        db.update("DELETE FROM request_types WHERE id=?", type);
        db.update("DELETE FROM subcategories WHERE id=?", subcategory);
        db.update("DELETE FROM categories WHERE id=?", category);
        db.update("DELETE FROM module_users WHERE citizen_id IN (?,?)", owner, stranger);
    }

    @ParameterizedTest @EnumSource(ModuleRole.class)
    void committedCodeIsIdenticalForEveryOwnerRoleAndReadHasNoBusinessWrites(ModuleRole role) throws Exception {
        var created = create(identity(owner, role));
        var before = snapshot(created.ticketId());
        assertThat(recovery.get(created.ticketId(), identity(owner, role)).trackingCode()).isEqualTo(created.trackingCode());
        mvc.perform(get(path(created.ticketId())).with(authentication(auth(owner, role))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("trackingCode", created.trackingCode()))));
        assertThat(snapshot(created.ticketId())).isEqualTo(before);
        assertThat(db.queryForObject("SELECT tracking_code_hash FROM tickets WHERE id=?", String.class, created.ticketId()))
                .isEqualTo(codes.hash(created.trackingCode()));
        assertThat(tracking.findByTrackingCode(created.trackingCode()).getPublicId()).isEqualTo(created.publicId());
        // New object, same configured test key: simulates recreation without key regeneration.
        var persisted = db.queryForMap("SELECT * FROM tickets WHERE id=?", created.ticketId());
        var restarted = new TrackingCodeCipher(environment.getProperty("app.tracking.encryption.key-base64"),
                environment.getProperty("app.tracking.encryption.key-id"), codes);
        assertThat(restarted.decrypt(created.ticketId(), owner, codes.hash(created.trackingCode()),
                new TrackingCodeCipher.EncryptedCode((byte[]) persisted.get("tracking_code_ciphertext"),
                        (byte[]) persisted.get("tracking_code_nonce"), (String) persisted.get("tracking_code_key_id"))))
                .isEqualTo(created.trackingCode());
    }

    @ParameterizedTest @EnumSource(ModuleRole.class)
    void strangerStaffAnonymousAndMissingAreUniformNotFound(ModuleRole role) throws Exception {
        var identified = create(identity(owner, ModuleRole.CITIZEN));
        var anonymous = create(null);
        for (UUID id : List.of(identified.ticketId(), anonymous.ticketId(), UUID.randomUUID())) {
            mvc.perform(get(path(id)).with(authentication(auth(stranger, role))))
                    .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().json("{\"code\":\"NOT_FOUND\",\"message\":\"El ticket solicitado no existe\"}"));
        }
        mvc.perform(get(path(identified.ticketId()))).andExpect(status().isUnauthorized());
    }

    @Test void historicalTicketRemainsReadableAndAnonymousCredentialsRemainUnchanged() throws Exception {
        var historical = create(identity(owner, ModuleRole.CITIZEN));
        db.update("UPDATE tickets SET tracking_code_ciphertext=NULL,tracking_code_nonce=NULL,tracking_code_key_id=NULL WHERE id=?", historical.ticketId());
        assertThat(tickets.getById(historical.ticketId(), identity(owner, ModuleRole.CITIZEN)).getPublicId()).isEqualTo(historical.publicId());
        assertThat(tracking.findByTrackingCode(historical.trackingCode()).getPublicId()).isEqualTo(historical.publicId());
        mvc.perform(get(path(historical.ticketId())).with(authentication(auth(owner, ModuleRole.CITIZEN))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRACKING_CODE_NOT_RECOVERABLE"));
        var anonymous = create(null);
        assertThat(db.queryForObject("SELECT tracking_code_ciphertext IS NULL AND tracking_code_nonce IS NULL AND tracking_code_key_id IS NULL FROM tickets WHERE id=?", Boolean.class, anonymous.ticketId())).isTrue();
        assertThat(anonymousAccess.authenticate(anonymous.trackingCode(), anonymous.generatedAnonymousAccessPassword()).ticketId()).isEqualTo(anonymous.ticketId());
        assertThat(tracking.findByTrackingCode(anonymous.trackingCode()).getPublicId()).isEqualTo(anonymous.publicId());
        verify(cipher, times(1)).encrypt(any(), any(), anyString());
    }

    @Test void tamperedCiphertextReturnsGenericNonCacheableErrorWithoutChangingHash() throws Exception {
        var created = create(identity(owner, ModuleRole.CITIZEN));
        byte[] altered = db.queryForObject("SELECT tracking_code_ciphertext FROM tickets WHERE id=?", byte[].class, created.ticketId());
        altered[0] ^= 1;
        db.update("UPDATE tickets SET tracking_code_ciphertext=? WHERE id=?", altered, created.ticketId());
        mvc.perform(get(path(created.ticketId())).with(authentication(auth(owner, ModuleRole.CITIZEN))))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"code\":\"TRACKING_CODE_UNAVAILABLE\",\"message\":\"El código de seguimiento no está disponible en este momento\"}"));
        assertThat(tracking.findByTrackingCode(created.trackingCode()).getPublicId()).isEqualTo(created.publicId());
    }

    @Test void encryptionAndLaterOutboxFailureRollbackAllPersistedEffects() {
        var before = counts();
        doThrow(new TrackingCodeUnavailableException()).when(cipher).encrypt(any(), any(), anyString());
        assertThatThrownBy(() -> create(identity(owner, ModuleRole.CITIZEN))).isInstanceOf(TrackingCodeUnavailableException.class);
        assertThat(counts()).isEqualTo(before);
        reset(cipher);
        TicketOutboxService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("test outbox failure")).when(target).ticketCreated(any(), any(), anyList(), any());
        assertThatThrownBy(() -> create(identity(owner, ModuleRole.CITIZEN))).isInstanceOf(IllegalStateException.class);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void persistenceFailureAlsoRollsBackInsteadOfConfirmingAnIncompleteCredential() {
        var existing = create(identity(owner, ModuleRole.CITIZEN));
        var material = db.queryForMap("SELECT tracking_code_ciphertext,tracking_code_nonce,tracking_code_key_id FROM tickets WHERE id=?", existing.ticketId());
        var before = counts();
        doReturn(new TrackingCodeCipher.EncryptedCode((byte[]) material.get("tracking_code_ciphertext"),
                (byte[]) material.get("tracking_code_nonce"), (String) material.get("tracking_code_key_id")))
                .when(cipher).encrypt(any(), any(), anyString());
        assertThatThrownBy(() -> create(identity(owner, ModuleRole.CITIZEN)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void secretsNeverAppearInGeneralResponsesOutboxNotificationsOrCsv() throws Exception {
        var created = create(identity(owner, ModuleRole.CITIZEN));
        var material = db.queryForMap("SELECT tracking_code_hash,tracking_code_ciphertext,tracking_code_nonce,tracking_code_key_id FROM tickets WHERE id=?", created.ticketId());
        var csv = new ByteArrayOutputStream();
        indicators.export(new IndicatorOverviewFilter(category, null, null, null, null, false, null, null), csv);
        String general = mapper.writeValueAsString(tickets.getById(created.ticketId(), identity(owner, ModuleRole.CITIZEN)))
                + mapper.writeValueAsString(tickets.listMyTickets(identity(owner, ModuleRole.CITIZEN), org.springframework.data.domain.PageRequest.of(0,20)))
                + mapper.writeValueAsString(tickets.getStaffDetail(created.ticketId(), identity(stranger, ModuleRole.ADMIN)))
                + db.queryForList("SELECT payload::text FROM outbox_events WHERE ticket_id=?", String.class, created.ticketId())
                + db.queryForList("SELECT * FROM notification_logs WHERE ticket_id=?", created.ticketId()) + csv;
        assertThat(general).doesNotContain(created.trackingCode(), (String) material.get("tracking_code_hash"),
                Base64.getEncoder().encodeToString((byte[]) material.get("tracking_code_ciphertext")),
                "trackingCodeCiphertext", "trackingCodeNonce", "trackingCodeKeyId");
    }

    @Test void concurrentCommittedCreationsAndReadsKeepDistinctMaterial() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<CreateTicketResponse>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> create(identity(owner, ModuleRole.CITIZEN)));
            var created = new ArrayList<CreateTicketResponse>();
            for (var result : pool.invokeAll(tasks)) created.add(result.get());
            assertThat(created).extracting(CreateTicketResponse::trackingCode).doesNotHaveDuplicates();
            var reads = new ArrayList<java.util.concurrent.Callable<String>>();
            for (var ticket : created) reads.add(() -> recovery.get(ticket.ticketId(), identity(owner, ModuleRole.CITIZEN)).trackingCode());
            for (int round = 0; round < 3; round++) {
                var results = pool.invokeAll(reads);
                for (int i = 0; i < created.size(); i++) assertThat(results.get(i).get()).isEqualTo(created.get(i).trackingCode());
            }
            assertThat(db.queryForObject("SELECT count(DISTINCT tracking_code_nonce) FROM tickets WHERE request_type_id=?", Integer.class, type)).isEqualTo(12);
        }
    }

    private CreateTicketResponse create(AuthenticatedIdentity identity) {
        return tickets.create(new CreateTicketRequest(type, marker, "Tracking test", Map.of(), null), identity, null);
    }
    private String path(UUID id) { return "/api/me/tickets/" + id + "/tracking-code"; }
    private AuthenticatedIdentity identity(UUID id, ModuleRole role) { return new AuthenticatedIdentity(id.toString(), id, "Test", null, role); }
    private UsernamePasswordAuthenticationToken auth(UUID id, ModuleRole role) {
        return new UsernamePasswordAuthenticationToken(identity(id,role),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    private List<Long> counts() {
        return List.of("tickets", "ticket_sla", "ticket_activities", "outbox_events", "notification_logs").stream()
                .map(table -> db.queryForObject("SELECT count(*) FROM " + table + " WHERE "
                        + (table.equals("tickets") ? "request_type_id=?" : "ticket_id IN (SELECT id FROM tickets WHERE request_type_id=?)"), Long.class, type)).toList();
    }
    private List<String> snapshot(UUID id) {
        return List.of("tickets", "ticket_sla", "ticket_activities", "outbox_events").stream()
                .map(table -> db.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.id)::text,'[]') FROM " + table + " t WHERE "
                        + (table.equals("tickets") ? "id=?" : "ticket_id=?"), String.class, id)).toList();
    }
}
