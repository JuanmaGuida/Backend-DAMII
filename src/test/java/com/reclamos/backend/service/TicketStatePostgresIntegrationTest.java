package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real controller, security, services, Flyway and PostgreSQL; every fixture rolls back. */
@SpringBootTest(properties = {"app.core.inbox.enabled=false", "app.core.outbox.enabled=false",
        "spring.jpa.show-sql=false", "ticket.priority.scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000", "notification.pending-scan-delay=3600000",
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class TicketStatePostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired TicketRepository tickets;
    @Autowired RequestTypeRepository types;
    @Autowired EntityManager entityManager;

    // Independent HTTP characterization: allowed states are omitted here because
    // their payload/authorization guards are covered by the existing use-case suites.
    static Stream<Arguments> rejectedHttpTransitions() {
        return Stream.of(
                new HttpAction("review", "{}", false, "TICKET_STATE_CONFLICT", "REGISTERED"),
                new HttpAction("route", "{}", false, "TICKET_STATE_CONFLICT", "IN_REVIEW"),
                new HttpAction("information-request", "{\"messageForCitizen\":\"Aporte contexto\"}", false,
                        "INFORMATION_REQUEST_CONFLICT", "IN_REVIEW ROUTED IN_PROGRESS"),
                new HttpAction("information-response", "{\"responseMessage\":\"Información adicional\"}", true,
                        "INFORMATION_REQUEST_CONFLICT", "PENDING_INFORMATION"),
                new HttpAction("resolution", "{\"type\":\"ACTION_COMPLETED\",\"publicMessage\":\"Resuelto\"}", false,
                        "TICKET_RESOLUTION_CONFLICT", "IN_PROGRESS"),
                new HttpAction("resolution/confirm", "{}", true, "TICKET_RESOLUTION_CONFLICT", "RESOLVED"),
                new HttpAction("resolution/reopen", "{\"reason\":\"Persiste el incidente\"}", true,
                        "TICKET_RESOLUTION_CONFLICT", "RESOLVED"),
                new HttpAction("cancel", "{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}", true,
                        "TICKET_STATE_CONFLICT", "REGISTERED"),
                new HttpAction("cancel", "{\"reasonCode\":\"OTHER\"}", false,
                        "TICKET_STATE_CONFLICT", "REGISTERED IN_REVIEW PENDING_INFORMATION"),
                new HttpAction("duplicate", "{}", false, "TICKET_STATE_CONFLICT",
                        "REGISTERED IN_REVIEW ROUTED IN_PROGRESS PENDING_INFORMATION"))
                .flatMap(action -> Arrays.stream(TicketStatus.values())
                        .filter(state -> !List.of(action.allowedStates().split(" ")).contains(state.name()))
                        .map(state -> Arguments.of(state, action)));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("rejectedHttpTransitions")
    void invalidHttpTransitionReturns409WithoutAnyPersistedEffects(TicketStatus state, HttpAction action) throws Exception {
        UUID owner = UUID.randomUUID();
        Ticket ticket = fixture(state, owner);
        Ticket main = fixture(TicketStatus.REGISTERED, UUID.randomUUID());
        entityManager.clear();
        Map<String, Object> before = snapshot(ticket.getId());
        Map<String, Object> mainBefore = snapshot(main.getId());
        ModuleRole role = action.owner() ? ModuleRole.CITIZEN : ModuleRole.AGENT;
        UUID actorId = action.owner() ? owner : UUID.randomUUID();
        var identity = new AuthenticatedIdentity(actorId.toString(), actorId, "State test", "M2", role);
        var auth = new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        boolean duplicate = action.path().equals("duplicate");
        String endpoint = (duplicate ? "/api/staff/tickets/" : "/api/tickets/") + ticket.getId() + "/" + action.path();
        String body = duplicate ? "{\"mainTicketId\":\"" + main.getId() + "\"}" : action.body();

        mvc.perform(post(endpoint).with(authentication(auth)).contentType("application/json").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(action.errorCode()));

        assertThat(snapshot(ticket.getId())).isEqualTo(before);
        assertThat(snapshot(main.getId())).isEqualTo(mainBefore);
    }

    private Ticket fixture(TicketStatus state, UUID owner) {
        db.update("INSERT INTO module_users(actor_id,actor_type,identity_source,citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?, 'CIUDADANO', 'MOCK', ?, 'State', 'Citizen', 'CITIZEN', TRUE, now(), now(), now())", owner, owner);
        RequestType type = types.findAll().stream().filter(RequestType::isActive).findFirst().orElseThrow();
        Ticket ticket = new Ticket();
        ticket.setPublicId("STATE-" + UUID.randomUUID());
        ticket.setTrackingCodeHash("state-" + UUID.randomUUID());
        ticket.setCitizenId(owner);
        ticket.setRequestType(type);
        ticket.setTicketType(type.getTicketType());
        ticket.setResponsibleAreaId("M2");
        ticket.setSummary("State HTTP fixture");
        ticket.setDescription("Isolated transition rejection");
        ticket.setCurrentStatus(state);
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setStatusChangedAt(Instant.parse("2026-11-01T10:00:00Z"));
        return tickets.saveAndFlush(ticket);
    }

    private Map<String, Object> snapshot(UUID id) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ticket", db.queryForMap("SELECT * FROM tickets WHERE id=?", id));
        for (String table : List.of("ticket_activities", "ticket_messages", "outbox_events", "notification_logs",
                "ticket_sla", "ticket_resolutions", "ticket_cancellations", "information_requests",
                "ticket_reclassification_proposals")) {
            snapshot.put(table, db.queryForList("SELECT * FROM " + table + " WHERE ticket_id=? ORDER BY id", id));
        }
        return snapshot;
    }

    private record HttpAction(String path, String body, boolean owner, String errorCode, String allowedStates) {
        @Override public String toString() { return path + (owner ? " (owner)" : " (staff)"); }
    }
}
