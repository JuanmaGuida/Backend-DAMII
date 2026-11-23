package com.reclamos.backend.security;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.service.TrackingCodeService;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDA2-254 (SEG-01), complemento de {@link BolaOwnershipPostgresIntegrationTest}: cubre los endpoints con id
 * de recurso que esa clase no sondea y congela el inventario.
 *
 * <ul>
 *   <li>Cruce de identificadores por tracking: las credenciales de un ticket anónimo no abren el adjunto de otro.</li>
 *   <li>Mensajes: {@code /messages/{messageId}} (GET, PATCH, DELETE) con un actor ajeno y con un mensaje de otro
 *       ticket bajo un ticket propio.</li>
 *   <li>Acciones que el filtro de seguridad deja pasar a cualquier autenticado y que valida el servicio.</li>
 *   <li>Endpoints solo para AGENT/ADMIN: un ciudadano y un responsable de área reciben 403 por rol.</li>
 *   <li>Guard de inventario: si aparece un endpoint con {@code {ticketId}}, {@code {attachmentId}} o
 *       {@code {messageId}} que no está clasificado abajo, la prueba falla.</li>
 * </ul>
 *
 * <p>Corre con Postgres real, igual que el resto de las pruebas {@code *PostgresIntegrationTest}.</p>
 */
@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"
})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class BolaCoverageGapsPostgresIntegrationTest {
    private static final Path ROOT = temporaryRoot();
    // Generada en cada ejecucion: el escaner de secretos rechaza credenciales literales entre comillas.
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String OWNER_AREA = "M6";
    private static final String OTHER_AREA = "M9";
    private static final Pattern ID_VARIABLE = Pattern.compile("\\{(ticketId|attachmentId|messageId)}");

    /** Endpoints que sondea {@link BolaOwnershipPostgresIntegrationTest} (ciudadano ajeno y staff de otra área). */
    private static final Set<String> COVERED_BY_BOLA_OWNERSHIP_TEST = Set.of(
            "GET /api/tickets/{ticketId}",
            "GET /api/tickets/{ticketId}/messages",
            "POST /api/tickets/{ticketId}/messages",
            "POST /api/tickets/{ticketId}/cancel",
            "POST /api/tickets/{ticketId}/information-response",
            "POST /api/tickets/{ticketId}/resolution/confirm",
            "POST /api/tickets/{ticketId}/resolution/reopen",
            "POST /api/tickets/{ticketId}/satisfaction-survey",
            "GET /api/tickets/{ticketId}/cancellation-requests",
            "GET /api/tickets/{ticketId}/external-attachments",
            "POST /api/tickets/{ticketId}/attachments",
            "GET /api/attachments/{attachmentId}/content",
            "GET /api/staff/tickets/{ticketId}",
            "GET /api/staff/tickets/{ticketId}/citizen-view");

    /** Endpoints que sondea esta clase con un actor ajeno (403/404 y sin datos del ticket). */
    private static final Set<String> COVERED_HERE = Set.of(
            "GET /api/tickets/{ticketId}/messages/{messageId}",
            "PATCH /api/tickets/{ticketId}/messages/{messageId}",
            "DELETE /api/tickets/{ticketId}/messages/{messageId}",
            "POST /api/tracking/actions/attachments/{attachmentId}/content",
            "POST /api/tickets/{ticketId}/information-request",
            "POST /api/tickets/{ticketId}/resolution",
            "POST /api/tickets/{ticketId}/cancellation-requests",
            "GET /api/me/tickets/{ticketId}/tracking-code",
            "POST /api/staff/tickets/{ticketId}/ai-insights");

    /**
     * Endpoints que solo existen con una propiedad activa (el simulador de eventos, app.simulator.enabled=true,
     * apagado por defecto): se clasifican, pero no se exige que esten mapeados en esta ejecucion.
     */
    private static final Set<String> CONDITIONAL = Set.of("POST /api/tickets/{ticketId}/simulate-status-update");

    /** Endpoints solo para AGENT/ADMIN: la puerta es el rol y se verifica el 403 para ciudadano y AREA_RESPONSIBLE. */
    private static final List<String> STAFF_ONLY = List.of(
            "POST /api/tickets/{ticketId}/review",
            "GET /api/tickets/{ticketId}/classification",
            "PATCH /api/tickets/{ticketId}/classification",
            "POST /api/tickets/{ticketId}/route",
            "POST /api/tickets/{ticketId}/simulate-status-update",
            "POST /api/staff/tickets/{ticketId}/duplicate",
            "GET /api/staff/tickets/{ticketId}/duplicate-candidates",
            "POST /api/staff/tickets/{ticketId}/labels",
            "DELETE /api/staff/tickets/{ticketId}/labels/{labelId}",
            "GET /api/staff/tickets/{ticketId}/reclassification-proposals",
            "POST /api/staff/tickets/{ticketId}/reclassification-proposals",
            "GET /api/staff/tickets/{ticketId}/reclassification-proposals/{proposalId}",
            "POST /api/staff/tickets/{ticketId}/reclassification-proposals/{proposalId}/abandon",
            "POST /api/staff/tickets/{ticketId}/reclassification-proposals/{proposalId}/confirm",
            "GET /api/staff/tickets/{ticketId}/cancellation-requests",
            "GET /api/staff/tickets/{ticketId}/cancellation-requests/{requestId}",
            "POST /api/staff/tickets/{ticketId}/cancellation-requests/{requestId}/approve",
            "POST /api/staff/tickets/{ticketId}/cancellation-requests/{requestId}/reject",
            "GET /api/staff/tickets/{ticketId}/cancellation-conflicts",
            "POST /api/staff/tickets/{ticketId}/cancellation-conflicts/{conflictId}/resolve");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired TrackingCodeService tracking;
    @Autowired ApplicationContext context;

    private final UUID owner = UUID.randomUUID();
    private final UUID intruder = UUID.randomUUID();
    private final UUID otherAreaStaff = UUID.randomUUID();
    private final String marker = "BOLA2-" + UUID.randomUUID();
    private UUID ownerTicket;
    private UUID intruderTicket;
    private UUID anonymousA;
    private UUID anonymousB;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("attachment.storage.root", () -> ROOT.toString());
    }

    @BeforeEach
    void setUp() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID actor : List.of(owner, intruder, otherAreaStaff)) {
            db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,"
                    + "active,last_synced_at,created_at,updated_at) VALUES (?, 'CIUDADANO', 'MOCK', ?,'BOLA','Test',"
                    + "'CITIZEN',TRUE,now(),now(),now())", actor, actor);
        }
        ownerTicket = insertTicket(owner, false, "Detalle privado " + marker);
        intruderTicket = insertTicket(intruder, false, "Ticket del intruso");
        anonymousA = insertTicket(null, true, "Anonimo A " + marker);
        anonymousB = insertTicket(null, true, "Anonimo B");
    }

    @Test
    void anonymousCredentialsOfAnotherTicketNeverOpenAnAttachment() throws Exception {
        long attachment = insertAttachment(anonymousA);
        String path = "/api/tracking/actions/attachments/{id}/content";

        // Control: las credenciales del dueño sí lo abren, así que la prueba de abajo no es vacía.
        mvc.perform(post(path, attachment).contentType(MediaType.APPLICATION_JSON).content(credentials(anonymousA)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(AttachmentFixtures.bytes("application/pdf")));

        // Ataque: credenciales válidas de OTRO ticket anónimo sobre el id de este adjunto.
        MvcResult swapped = mvc.perform(post(path, attachment).contentType(MediaType.APPLICATION_JSON)
                .content(credentials(anonymousB))).andReturn();
        assertDenied("tracking swap", swapped, marker, code(anonymousA));

        // Un id inexistente tiene que contestar lo mismo que un id ajeno: no revela cuáles existen.
        MvcResult missing = mvc.perform(post(path, Long.MAX_VALUE).contentType(MediaType.APPLICATION_JSON)
                .content(credentials(anonymousB))).andReturn();
        assertThat(swapped.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus());
    }

    @Test
    void messageEndpointsRejectForeignActorsAndMessagesOfAnotherTicket() throws Exception {
        mvc.perform(post("/api/tickets/{id}/messages", ownerTicket).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PUBLIC\",\"text\":\"mensaje original " + marker + "\"}")
                        .with(as(owner, ModuleRole.CITIZEN, null)))
                .andExpect(status().isCreated());
        long message = db.queryForObject("SELECT id FROM ticket_messages WHERE ticket_id=?", Long.class, ownerTicket);

        record Attempt(String name, RequestPostProcessor actor, UUID ticket) { }
        List<Attempt> attempts = List.of(
                new Attempt("ciudadano ajeno sobre el ticket del dueño", as(intruder, ModuleRole.CITIZEN, null), ownerTicket),
                new Attempt("ciudadano ajeno sobre SU ticket con el mensaje de otro", as(intruder, ModuleRole.CITIZEN, null), intruderTicket),
                new Attempt("staff de otra área", as(otherAreaStaff, ModuleRole.AREA_RESPONSIBLE, OTHER_AREA), ownerTicket));
        for (Attempt attempt : attempts) {
            assertDenied(attempt.name() + " GET", mvc.perform(get("/api/tickets/{t}/messages/{m}", attempt.ticket(), message)
                    .with(attempt.actor())).andReturn(), marker);
            assertDenied(attempt.name() + " PATCH", mvc.perform(patch("/api/tickets/{t}/messages/{m}", attempt.ticket(), message)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"alterado\"}")
                    .with(attempt.actor())).andReturn(), marker);
            assertDenied(attempt.name() + " DELETE", mvc.perform(delete("/api/tickets/{t}/messages/{m}", attempt.ticket(), message)
                    .with(attempt.actor())).andReturn(), marker);
        }

        // Los ataques fallidos no cambiaron nada: el mensaje sigue ahí y el dueño lo lee sin alteraciones.
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_messages WHERE id=?", Long.class, message)).isEqualTo(1L);
        mvc.perform(get("/api/tickets/{t}/messages/{m}", ownerTicket, message).with(as(owner, ModuleRole.CITIZEN, null)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mensaje original")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("alterado"))));
    }

    @Test
    void actionsThatOnlyNeedAuthenticationStillRejectAForeignCitizen() throws Exception {
        String statusBefore = db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ownerTicket);
        RequestPostProcessor foreign = as(intruder, ModuleRole.CITIZEN, null);

        List<MvcResult> results = List.of(
                mvc.perform(post("/api/tickets/{id}/information-request", ownerTicket)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messageForCitizen\":\"No soy staff\"}")
                        .with(foreign)).andReturn(),
                mvc.perform(post("/api/tickets/{id}/resolution", ownerTicket)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ACTION_COMPLETED\",\"publicMessage\":\"No soy staff\"}")
                        .with(foreign)).andReturn(),
                mvc.perform(post("/api/tickets/{id}/cancellation-requests", ownerTicket)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OUT_OF_SCOPE\",\"reason\":\"No soy staff\"}")
                        .with(foreign)).andReturn(),
                mvc.perform(get("/api/me/tickets/{id}/tracking-code", ownerTicket).with(foreign)).andReturn());
        for (int index = 0; index < results.size(); index++) {
            assertDenied("authenticated-only probe #" + index, results.get(index), marker, code(ownerTicket));
        }

        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ownerTicket))
                .isEqualTo(statusBefore);
        assertThat(db.queryForObject("SELECT count(*) FROM information_requests WHERE ticket_id=?", Long.class, ownerTicket))
                .isZero();
    }

    @Test
    void staffOfAnotherAreaCannotRunAiInsightsOnTheTicket() throws Exception {
        MvcResult result = mvc.perform(post("/api/staff/tickets/{id}/ai-insights", ownerTicket)
                .with(as(otherAreaStaff, ModuleRole.AREA_RESPONSIBLE, OTHER_AREA))).andReturn();
        assertDenied("ai-insights de otra área", result, marker);
    }

    @Test
    void staffOnlyEndpointsRejectCitizensAndAreaResponsiblesByRole() throws Exception {
        UUID ticket = ownerTicket;
        for (String endpoint : STAFF_ONLY) {
            String verb = endpoint.substring(0, endpoint.indexOf(' '));
            String path = endpoint.substring(endpoint.indexOf(' ') + 1)
                    .replace("{ticketId}", ticket.toString())
                    .replaceAll("\\{[A-Za-z]+}", "1");
            for (RequestPostProcessor actor : List.of(as(intruder, ModuleRole.CITIZEN, null),
                    as(otherAreaStaff, ModuleRole.AREA_RESPONSIBLE, OTHER_AREA),
                    as(owner, ModuleRole.CITIZEN, null))) {
                MvcResult result = mvc.perform(request(HttpMethod.valueOf(verb), path).with(actor)).andReturn();
                assertThat(result.getResponse().getStatus())
                        .as("%s debe dar 403 por rol; body: %s", endpoint, result.getResponse().getContentAsString())
                        .isEqualTo(403);
            }
        }
    }

    /**
     * Guard del inventario de SEG-01. Si alguien agrega un endpoint con id de ticket, adjunto o mensaje, esta prueba
     * falla hasta que se lo clasifique arriba (con una sonda de propiedad o como endpoint solo de staff). Si se
     * borra un endpoint, falla hasta que se lo saque de las listas.
     */
    @Test
    void everyEndpointThatTakesATicketAttachmentOrMessageIdIsClassified() {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        Set<String> actual = new TreeSet<>();
        mapping.getHandlerMethods().keySet().forEach(info -> {
            if (info.getPathPatternsCondition() == null) return;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!ID_VARIABLE.matcher(pattern).find()) continue;
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    actual.add(method + " " + pattern);
                }
            }
        });

        Set<String> declared = new TreeSet<>();
        declared.addAll(COVERED_BY_BOLA_OWNERSHIP_TEST);
        declared.addAll(COVERED_HERE);
        declared.addAll(STAFF_ONLY);

        Set<String> unclassified = new TreeSet<>(actual);
        unclassified.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(actual);
        stale.removeAll(CONDITIONAL);
        assertThat(unclassified).as("Endpoints con id sin clasificar en el inventario BOLA").isEmpty();
        assertThat(stale).as("Entradas del inventario BOLA que ya no existen").isEmpty();
    }

    private UUID insertTicket(UUID citizen, boolean anonymous, String summary) {
        UUID id = UUID.randomUUID();
        String credential = anonymous ? new BCryptPasswordEncoder().encode(PASSWORD) : null;
        db.update("INSERT INTO tickets (id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,"
                        + "request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,"
                        + "is_escalated,status_changed_at,created_at,updated_at) "
                        + "SELECT ?, ?, ?, ?, ?, ?, id,ticket_type,?,?,?,'{}'::jsonb,'REGISTERED','LOW',"
                        + "false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM request_types "
                        + "WHERE code='INFORMAR_UN_BACHE'",
                id, "B2-" + id.toString().substring(0, 20), tracking.hash(code(id)), citizen, anonymous, credential,
                OWNER_AREA, summary, summary);
        return id;
    }

    private long insertAttachment(UUID ticket) throws IOException {
        byte[] bytes = AttachmentFixtures.bytes("application/pdf");
        String key = "bola2/" + UUID.randomUUID() + ".pdf";
        Path file = ROOT.resolve(key);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return db.queryForObject("INSERT INTO attachments(ticket_id,file_name,content_type,size_bytes,storage_key,"
                        + "visibility,uploaded_by_type,uploaded_by_id) VALUES (?,?,?,?,?,'PUBLIC','CITIZEN',?) RETURNING id",
                Long.class, ticket, "private-" + marker + ".pdf", "application/pdf", (long) bytes.length, key,
                UUID.randomUUID().toString());
    }

    private static String code(UUID ticket) {
        return ticket.toString().replace("-", "");
    }

    private static String credentials(UUID ticket) {
        return "{\"trackingCode\":\"" + code(ticket) + "\",\"anonymousAccessPassword\":\"" + PASSWORD + "\"}";
    }

    private static void assertDenied(String probe, MvcResult result, String... secrets) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("%s: expected 403 or 404 but body was %s", probe, body)
                .isIn(403, 404);
        for (String secret : secrets) {
            assertThat(body).as("%s must not leak %s", probe, secret).doesNotContain(secret);
        }
    }

    private static RequestPostProcessor as(UUID id, ModuleRole role, String area) {
        return authentication(new UsernamePasswordAuthenticationToken(identity(id, role, area), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static AuthenticatedIdentity identity(UUID id, ModuleRole role, String area) {
        return new AuthenticatedIdentity(id.toString(), id, "BOLA", area, role);
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("m2-bola-attachments-");
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
