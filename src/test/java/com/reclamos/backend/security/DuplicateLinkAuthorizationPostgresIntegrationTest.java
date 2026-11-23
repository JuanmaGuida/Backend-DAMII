package com.reclamos.backend.security;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.service.TrackingCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDA2-254 (SEG-01): prueba HTTP + PostgreSQL de {@code POST /api/staff/tickets/{ticketId}/duplicate} con el
 * {@code mainTicketId} del body, que {@link BolaCoverageGapsPostgresIntegrationTest} solo sondea por rol y sin body.
 *
 * <p>Qué fija esta clase:</p>
 * <ul>
 *   <li><b>Control positivo:</b> un AGENT vincula un duplicado a un principal de OTRA área y el vínculo queda
 *       persistido. Es el diseño vigente: AGENT y ADMIN operan sobre tickets de cualquier área (Entidades M2 §3.3,
 *       Decisiones y adaptaciones M2 §12); el {@code areaId} solo acota a AREA_RESPONSIBLE, que no hace triage.</li>
 *   <li><b>Denegados:</b> sin autenticar, ciudadano ajeno, ciudadano dueño del duplicado y AREA_RESPONSIBLE (del área
 *       del duplicado y de otra) reciben 401/403 con un principal de la misma área y con uno de otra área.</li>
 *   <li><b>Sin mutaciones:</b> tras los intentos denegados no cambian estado, vínculos, actividad ni outbox, y la
 *       respuesta no devuelve datos de los tickets.</li>
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
class DuplicateLinkAuthorizationPostgresIntegrationTest {
    private static final String LINK_PATH = "/api/staff/tickets/{ticketId}/duplicate";
    private static final String DUPLICATE_AREA = "M6";
    private static final String OTHER_AREA = "M9";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired TrackingCodeService tracking;

    private final UUID duplicateOwner = UUID.randomUUID();
    private final UUID mainOwner = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID areaResponsible = UUID.randomUUID();
    private final String marker = "DUPLINK-" + UUID.randomUUID();
    private UUID duplicate;
    private UUID mainSameArea;
    private UUID mainOtherArea;

    @BeforeEach
    void setUp() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID citizen : List.of(duplicateOwner, mainOwner)) {
            db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,"
                    + "active,last_synced_at,created_at,updated_at) VALUES (?, 'CIUDADANO', 'MOCK', ?,'DUPLINK','Test',"
                    + "'CITIZEN',TRUE,now(),now(),now())", citizen, citizen);
        }
        duplicate = insertTicket(duplicateOwner, DUPLICATE_AREA, "Duplicado " + marker);
        mainSameArea = insertTicket(mainOwner, DUPLICATE_AREA, "Principal misma area " + marker);
        mainOtherArea = insertTicket(mainOwner, OTHER_AREA, "Principal otra area " + marker);
    }

    @Test
    void agentLinksADuplicateToAMainTicketOfAnotherAreaByDesign() throws Exception {
        long activitiesBefore = activityCount();

        mvc.perform(post(LINK_PATH, duplicate).contentType(MediaType.APPLICATION_JSON)
                        .content(body(mainOtherArea))
                        .with(as(agent, ModuleRole.AGENT, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(duplicate.toString()))
                .andExpect(jsonPath("$.mainTicketId").value(mainOtherArea.toString()))
                .andExpect(jsonPath("$.currentStatus").value("DUPLICATE"));

        assertThat(db.queryForObject("SELECT main_ticket_id FROM tickets WHERE id=?", UUID.class, duplicate))
                .isEqualTo(mainOtherArea);
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, duplicate))
                .isEqualTo("DUPLICATE");
        assertThat(activityCount()).isEqualTo(activitiesBefore + 1);
        // El principal no se modifica por recibir un duplicado.
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, mainOtherArea))
                .isEqualTo("REGISTERED");
        assertThat(db.queryForObject("SELECT main_ticket_id FROM tickets WHERE id=?", UUID.class, mainOtherArea))
                .isNull();
    }

    @Test
    void actorsWithoutTriageAuthorityCannotLinkAndNothingChanges() throws Exception {
        List<Object> before = state();

        record Attempt(String name, RequestPostProcessor actor, List<Integer> allowedStatuses) { }
        List<Attempt> attempts = List.of(
                new Attempt("sin autenticar", null, List.of(401, 403)),
                new Attempt("ciudadano ajeno", as(UUID.randomUUID(), ModuleRole.CITIZEN, null), List.of(403)),
                new Attempt("ciudadano dueño del duplicado", as(duplicateOwner, ModuleRole.CITIZEN, null), List.of(403)),
                new Attempt("responsable del área del duplicado",
                        as(areaResponsible, ModuleRole.AREA_RESPONSIBLE, DUPLICATE_AREA), List.of(403)),
                new Attempt("responsable de otra área",
                        as(areaResponsible, ModuleRole.AREA_RESPONSIBLE, OTHER_AREA), List.of(403)));

        for (Attempt attempt : attempts) {
            for (UUID main : List.of(mainSameArea, mainOtherArea)) {
                MockHttpServletRequestBuilder request = post(LINK_PATH, duplicate)
                        .contentType(MediaType.APPLICATION_JSON).content(body(main));
                if (attempt.actor() != null) {
                    request = request.with(attempt.actor());
                }
                MvcResult result = mvc.perform(request).andReturn();
                String responseBody = result.getResponse().getContentAsString();
                assertThat(result.getResponse().getStatus())
                        .as("%s con principal %s; body: %s", attempt.name(), main, responseBody)
                        .isIn(attempt.allowedStatuses());
                assertThat(responseBody)
                        .as("%s no debe devolver datos de los tickets", attempt.name())
                        .doesNotContain(marker)
                        .doesNotContain(code(mainSameArea))
                        .doesNotContain(code(mainOtherArea))
                        .doesNotContain(code(duplicate));
            }
        }

        // Ningún intento denegado cambió estado, vínculos, actividad ni outbox.
        assertThat(state()).isEqualTo(before);
        assertThat(db.queryForObject("SELECT main_ticket_id FROM tickets WHERE id=?", UUID.class, duplicate)).isNull();
    }

    /** Foto de lo que un vínculo indebido tocaría: tickets (estado/vínculo/versión temporal), actividad y outbox. */
    private List<Object> state() {
        List<Map<String, Object>> tickets = db.queryForList(
                "SELECT id, current_status, main_ticket_id, updated_at FROM tickets WHERE id IN (?,?,?) ORDER BY id",
                duplicate, mainSameArea, mainOtherArea);
        Long outbox = db.queryForObject("SELECT count(*) FROM outbox_events", Long.class);
        return List.of(tickets, activityCount(), outbox);
    }

    private long activityCount() {
        return db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id IN (?,?,?)", Long.class,
                duplicate, mainSameArea, mainOtherArea);
    }

    private UUID insertTicket(UUID citizen, String area, String summary) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tickets (id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,"
                        + "request_type_id,ticket_type,responsible_area_id,summary,description,form_data,current_status,current_priority,"
                        + "is_escalated,status_changed_at,created_at,updated_at) "
                        + "SELECT ?, ?, ?, ?, ?, ?, id,ticket_type,?,?,?,'{}'::jsonb,'REGISTERED','LOW',"
                        + "false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM request_types "
                        + "WHERE code='INFORMAR_UN_BACHE'",
                id, "DL-" + id.toString().substring(0, 20), tracking.hash(code(id)), citizen, false, null,
                area, summary, summary);
        return id;
    }

    private static String body(UUID mainTicketId) {
        return "{\"mainTicketId\":\"" + mainTicketId + "\"}";
    }

    private static String code(UUID ticket) {
        return ticket.toString().replace("-", "");
    }

    private static RequestPostProcessor as(UUID id, ModuleRole role, String area) {
        return authentication(new UsernamePasswordAuthenticationToken(identity(id, role, area), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static AuthenticatedIdentity identity(UUID id, ModuleRole role, String area) {
        return new AuthenticatedIdentity(id.toString(), id, "DUPLINK", area, role);
    }
}
