package com.reclamos.backend.security;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.service.TicketService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDA2-254 (SEG-01): BOLA/IDOR. Hay un ticket y un adjunto que pertenecen a un ciudadano; otro ciudadano
 * autenticado (y personal de otra área) prueba, uno por uno, todos los endpoints que reciben el id de ese
 * recurso. Todos deben contestar 403 o 404 y ninguna respuesta puede traer datos del ticket.
 *
 * <p>Si se agrega un endpoint con {@code {ticketId}} o {@code {attachmentId}}, tiene que sumarse a
 * {@link #foreignCitizenProbes} o a {@link #foreignStaffProbes}: es el inventario de este criterio.
 * Corre con Postgres real, igual que el resto de las pruebas {@code *PostgresIntegrationTest}.</p>
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class BolaOwnershipPostgresIntegrationTest {
    private static final String OWNER_AREA = "M6";
    private static final String OTHER_AREA = "M9";

    @Autowired TicketService tickets;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;

    private final UUID owner = UUID.randomUUID();
    private final UUID intruder = UUID.randomUUID();
    private final UUID otherAreaStaff = UUID.randomUUID();
    private final String marker = "BOLA-" + UUID.randomUUID();
    private UUID ticketId;
    private long attachmentId;

    @BeforeEach
    void setUp() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID actor : List.of(owner, intruder, otherAreaStaff)) {
            db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,"
                    + "active,last_synced_at,created_at,updated_at) VALUES (?, 'CIUDADANO', 'MOCK', ?,'BOLA','Test',"
                    + "'CITIZEN',TRUE,now(),now(),now())", actor, actor);
        }
        Long type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,"
                + "responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,"
                + "requires_location,active) SELECT ?,id,?,?,'REQUEST','M6','LOW','LOW',0,TRUE,FALSE,TRUE "
                + "FROM subcategories ORDER BY id LIMIT 1 RETURNING id", Long.class, marker, marker, marker);
        ticketId = tickets.create(new CreateTicketRequest(type, marker, "Detalle privado " + marker, Map.of(), null),
                identity(owner, ModuleRole.CITIZEN, null), null).ticketId();
        em.flush();
        db.update("UPDATE tickets SET responsible_area_id=? WHERE id=?", OWNER_AREA, ticketId);
        attachmentId = db.queryForObject("INSERT INTO attachments(ticket_id,file_name,content_type,size_bytes,storage_key,"
                        + "visibility,uploaded_by_type,uploaded_by_id) VALUES (?,?,?,?,?,'PUBLIC','CITIZEN',?) RETURNING id",
                Long.class, ticketId, "private-" + marker + ".pdf", "application/pdf", 10L,
                "bola/" + marker, owner.toString());
        em.clear();
    }

    private final List<Function<UUID, AbstractMockHttpServletRequestBuilder<?>>> foreignCitizenProbes = List.of(
            ticket -> get("/api/tickets/{id}", ticket),
            ticket -> get("/api/tickets/{id}/messages", ticket),
            ticket -> post("/api/tickets/{id}/messages", ticket).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"visibility\":\"PUBLIC\",\"text\":\"No soy el dueño\"}"),
            ticket -> post("/api/tickets/{id}/cancel", ticket).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}"),
            ticket -> post("/api/tickets/{id}/information-response", ticket).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"responseMessage\":\"No soy el dueño\"}"),
            ticket -> post("/api/tickets/{id}/resolution/confirm", ticket),
            ticket -> post("/api/tickets/{id}/resolution/reopen", ticket).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"No soy el dueño\"}"),
            ticket -> post("/api/tickets/{id}/satisfaction-survey", ticket).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"score\":5}"),
            ticket -> get("/api/tickets/{id}/cancellation-requests", ticket),
            ticket -> get("/api/tickets/{id}/external-attachments", ticket),
            ticket -> multipart("/api/tickets/{id}/attachments", ticket)
                    .file(new MockMultipartFile("attachments", "intruso.pdf", "application/pdf",
                            com.reclamos.backend.support.AttachmentFixtures.bytes("application/pdf")))
                    .part(visibilityPart()),
            ticket -> get("/api/attachments/{id}/content", attachmentId)
    );

    private final List<Function<UUID, AbstractMockHttpServletRequestBuilder<?>>> foreignStaffProbes = List.of(
            ticket -> get("/api/staff/tickets/{id}", ticket),
            ticket -> get("/api/staff/tickets/{id}/citizen-view", ticket),
            ticket -> post("/api/tickets/{id}/messages", ticket).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"visibility\":\"INTERNAL\",\"text\":\"Otra área\"}"),
            ticket -> get("/api/tickets/{id}/messages", ticket),
            ticket -> get("/api/attachments/{id}/content", attachmentId)
    );

    @Test
    void theOwnerCanReadItsOwnTicketSoTheProbesBelowAreNotVacuous() throws Exception {
        mvc.perform(get("/api/tickets/{id}", ticketId).with(as(owner, ModuleRole.CITIZEN, null)))
                .andExpect(status().isOk());
    }

    @Test
    void aForeignCitizenGets403Or404OnEveryEndpointThatTakesTheTicketOrAttachmentId() throws Exception {
        RequestPostProcessor foreign = as(intruder, ModuleRole.CITIZEN, null);
        for (int index = 0; index < foreignCitizenProbes.size(); index++) {
            MvcResult result = mvc.perform(foreignCitizenProbes.get(index).apply(ticketId).with(foreign)).andReturn();
            assertDenied("citizen probe #" + index, result);
        }
    }

    @Test
    void staffOfAnotherAreaGets403Or404WhenItReachesForTheTicket() throws Exception {
        RequestPostProcessor foreignStaff = as(otherAreaStaff, ModuleRole.AREA_RESPONSIBLE, OTHER_AREA);
        for (int index = 0; index < foreignStaffProbes.size(); index++) {
            MvcResult result = mvc.perform(foreignStaffProbes.get(index).apply(ticketId).with(foreignStaff)).andReturn();
            assertDenied("staff probe #" + index, result);
        }
    }

    @Test
    void theFailedAttacksChangedNothing() throws Exception {
        String statusBefore = db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticketId);
        RequestPostProcessor foreign = as(intruder, ModuleRole.CITIZEN, null);
        for (Function<UUID, AbstractMockHttpServletRequestBuilder<?>> probe : foreignCitizenProbes) {
            mvc.perform(probe.apply(ticketId).with(foreign));
        }
        em.clear();

        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticketId))
                .isEqualTo(statusBefore);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id=?", Long.class, ticketId))
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM attachments WHERE ticket_id=?", Long.class, ticketId))
                .isEqualTo(1L);
        assertThat(db.queryForObject("SELECT count(*) FROM satisfaction_surveys WHERE ticket_id=?", Long.class, ticketId))
                .isZero();
    }

    private void assertDenied(String probe, MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("%s: expected 403 or 404 but body was %s", probe, body)
                .isIn(403, 404);
        assertThat(body).as("%s must not leak the ticket", probe)
                .doesNotContain(marker).doesNotContain("Detalle privado").doesNotContain(owner.toString());
    }

    private static MockPart visibilityPart() {
        MockPart data = new MockPart("data", "{\"visibility\":\"PUBLIC\"}".getBytes());
        data.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return data;
    }

    private static RequestPostProcessor as(UUID id, ModuleRole role, String area) {
        return authentication(new UsernamePasswordAuthenticationToken(identity(id, role, area), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static AuthenticatedIdentity identity(UUID id, ModuleRole role, String area) {
        return new AuthenticatedIdentity(id.toString(), id, "BOLA", area, role);
    }
}
