package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.UpdateTicketStatusEnvelope;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.exception.TicketResolutionConflictException;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.OutboxEventRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"ticket.priority.scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000",
        "ticket.information-request.expiration-scan-delay=3600000", "ticket.resolution.confirmation-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"})
@ActiveProfiles("dev")
class TicketSlaCompletionEscalationPostgresTest {
    @Autowired TicketService tickets;
    @Autowired TicketResolutionService resolutions;
    @Autowired TicketSlaMilestoneService milestones;
    @Autowired TicketStatusUpdateService external;
    @Autowired TicketReclassificationService reclassification;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate db;
    private final List<UUID> ids = new ArrayList<>();
    private final List<Long> types = new ArrayList<>();
    private final List<UUID> inboxIds = new ArrayList<>();
    private final UUID citizen = UUID.randomUUID(), agent = UUID.randomUUID();
    private final String marker = "QA02-CLOSE-" + UUID.randomUUID();

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID id : List.of(citizen, agent)) {
            db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                    + "VALUES (?,'QA02','Cierre',?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id, id.equals(agent) ? "AGENT" : "CITIZEN");
        }
    }

    @AfterEach void cleanup() {
        for (UUID id : ids) {
            for (String table : List.of("ticket_reclassification_proposals", "notification_logs", "outbox_events",
                    "ticket_resolutions", "ticket_cancellations", "ticket_messages", "ticket_activities", "ticket_sla", "ticket_locations")) {
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            }
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        for (Long id : types) db.update("DELETE FROM request_types WHERE id=?", id);
        for (UUID id : inboxIds) db.update("DELETE FROM inbox_events WHERE event_id=?", id);
        for (UUID id : List.of(citizen, agent)) db.update("DELETE FROM module_users WHERE citizen_id=?", id);
    }

    @ParameterizedTest @CsvSource({
            "REVIEW,LOW,false", "REVIEW,CRITICAL,false", "REVIEW,CRITICAL,true",
            "RESOLVE,LOW,false", "RESOLVE,CRITICAL,false", "RESOLVE,CRITICAL,true",
            "CANCEL,LOW,false", "CANCEL,CRITICAL,false", "CANCEL,CRITICAL,true"})
    void functionalCompletionImmediatelyDropsHistoricalBreach(String operation, Priority priority, boolean manual) {
        UUID id = create(priority);
        if (operation.equals("RESOLVE")) startManagement(id);
        if (manual) db.update("UPDATE tickets SET escalation_reason_code='MANUAL' WHERE id=?", id);
        List<SlaType> closing = operation.equals("CANCEL") ? List.of(SlaType.FIRST_RESPONSE, SlaType.RESOLUTION)
                : List.of(operation.equals("REVIEW") ? SlaType.FIRST_RESPONSE : SlaType.RESOLUTION);
        for (SlaType type : closing) {
            makeOverdue(id, type);
            milestones.processCandidate(id, slaId(id, type), Instant.now());
        }
        Object activated = ticket(id).get("escalated_at");
        int activations = activities(id, "ESCALATED");
        int events = escalationEvents(id);
        int outboxSize = db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=?", Integer.class, id);
        perform(id, operation);

        String expected = manual ? "MANUAL" : priority == Priority.CRITICAL ? "CRITICAL_PRIORITY" : null;
        assertThat(ticket(id)).containsEntry("escalation_reason_code", expected)
                .containsEntry("is_escalated", expected != null)
                .containsEntry("escalated_at", expected == null ? null : activated)
                .containsEntry("current_priority", priority.name());
        for (SlaType type : closing) {
            assertThat(db.queryForMap("SELECT status,completed_at FROM ticket_sla WHERE ticket_id=? AND sla_type=?", id, type.name()))
                    .containsEntry("status", "BREACHED").doesNotContainEntry("completed_at", null);
        }
        assertThat(activities(id, "ESCALATED")).isEqualTo(activations);
        assertThat(escalationEvents(id)).isEqualTo(events + (manual ? 0 : 1));
        List<String> updates = db.queryForList("SELECT update_type FROM outbox_events WHERE ticket_id=? ORDER BY id", String.class, id);
        assertThat(updates.subList(outboxSize, updates.size())).containsExactlyElementsOf(manual
                ? List.of(operation.equals("REVIEW") ? "STATUS_CHANGED" : operation.equals("RESOLVE") ? "RESOLVED" : "CANCELLED")
                : List.of("ESCALATION_CHANGED", operation.equals("REVIEW") ? "STATUS_CHANGED" : operation.equals("RESOLVE") ? "RESOLVED" : "CANCELLED"));
        int historySize = db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id);
        assertThatThrownBy(() -> perform(id, operation))
                .isInstanceOfAny(TicketStateConflictException.class, TicketResolutionConflictException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id))
                .isEqualTo(historySize);
        assertThat(escalationEvents(id)).isEqualTo(events + (manual ? 0 : 1));
        if (operation.equals("RESOLVE")) {
            resolutions.confirm(id, identity(citizen, ModuleRole.CITIZEN));
            assertThat(ticket(id)).containsEntry("current_status", "CLOSED").containsEntry("escalation_reason_code", expected);
            assertThat(escalationEvents(id)).isEqualTo(events + (manual ? 0 : 1));
        }
        validateEvents(id);
    }

    @ParameterizedTest @CsvSource({
            "REVIEW,LOW,false", "REVIEW,CRITICAL,false", "REVIEW,CRITICAL,true",
            "RESOLVE,LOW,false", "RESOLVE,CRITICAL,false", "RESOLVE,CRITICAL,true",
            "CANCEL,LOW,false", "CANCEL,CRITICAL,false", "CANCEL,CRITICAL,true"})
    void unobservedBreachAtCompletionRecordsMilestonesWithoutTransientEscalation(String operation, Priority priority, boolean manual) {
        UUID id = create(priority);
        if (operation.equals("RESOLVE")) startManagement(id);
        if (manual) db.update("UPDATE tickets SET escalation_reason_code='MANUAL' WHERE id=?", id);
        Object activated = ticket(id).get("escalated_at");
        int activations = activities(id, "ESCALATED");
        List<SlaType> closing = operation.equals("CANCEL") ? List.of(SlaType.FIRST_RESPONSE, SlaType.RESOLUTION)
                : List.of(operation.equals("REVIEW") ? SlaType.FIRST_RESPONSE : SlaType.RESOLUTION);
        for (SlaType type : closing) makeOverdue(id, type);
        perform(id, operation);

        String expected = manual ? "MANUAL" : priority == Priority.CRITICAL ? "CRITICAL_PRIORITY" : null;
        assertThat(ticket(id)).containsEntry("is_escalated", expected != null).containsEntry("escalation_reason_code", expected)
                .containsEntry("escalated_at", activated);
        assertThat(activities(id, "SLA_BREACHED")).isEqualTo(closing.size());
        assertThat(activities(id, "SLA_NEAR_DUE")).isEqualTo(closing.size());
        assertThat(activities(id, "ESCALATED")).isEqualTo(activations);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? AND jsonb_exists(metadata,'escalationActive')", Integer.class, id))
                .isEqualTo(activations);
        assertThat(escalationEvents(id)).isZero();
        if (operation.equals("RESOLVE")) {
            assertThat(db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=? AND type='SLA_BREACHED'", Integer.class, id))
                    .isEqualTo(1);
        }
        validateEvents(id);
    }

    @Test void firstResponseCompletionKeepsAnotherActiveBreachedCycle() {
        UUID id = create(Priority.CRITICAL);
        for (SlaType type : SlaType.values()) {
            makeOverdue(id, type);
            milestones.processCandidate(id, slaId(id, type), Instant.now());
        }
        Object activated = ticket(id).get("escalated_at");
        int events = escalationEvents(id);
        tickets.startReview(id, staff());
        assertThat(ticket(id)).containsEntry("escalation_reason_code", "SLA_BREACHED").containsEntry("escalated_at", activated);
        assertThat(escalationEvents(id)).isEqualTo(events);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_sla WHERE ticket_id=? AND status='BREACHED' AND completed_at IS NULL", Integer.class, id))
                .isEqualTo(1);
    }

    @Test void externalResolutionRecordsAnUnobservedBreachWithoutTransientEscalationAndReplayIsIdempotent() {
        UUID id = create(Priority.LOW, "M6");
        startManagement(id);
        makeOverdue(id, SlaType.RESOLUTION);
        UUID eventId = UUID.randomUUID(); inboxIds.add(eventId);
        Instant now = Instant.now();
        var incoming = new UpdateTicketStatusEnvelope("1.0", eventId, "updateTicketStatus", now,
                new UpdateTicketStatusEnvelope.Producer("M6", "qa02-close"), "tickets/" + id,
                new UpdateTicketStatusRequest(id, UpdateTicketStatusType.RESOLVED, "Solicitud atendida", null, null,
                        new UpdateTicketStatusRequest.Details(null, null,
                                new UpdateTicketStatusRequest.Resolution(ResolutionType.REQUEST_FULFILLED), null),
                        new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", agent.toString()), now));
        external.applyUpdate(id, incoming);
        assertThat(ticket(id)).containsEntry("current_status", "RESOLVED")
                .containsEntry("is_escalated", false).containsEntry("escalation_reason_code", null);
        assertThat(activities(id, "SLA_BREACHED")).isEqualTo(1);
        assertThat(activities(id, "ESCALATED")).isZero();
        assertThat(escalationEvents(id)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=? AND type='SLA_BREACHED'", Integer.class, id))
                .isEqualTo(1);
        var beforeReplay = ticket(id);
        int audit = db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id);
        external.applyUpdate(id, incoming);
        assertThat(ticket(id)).isEqualTo(beforeReplay);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id)).isEqualTo(audit);
        assertThat(escalationEvents(id)).isZero();
    }

    @ParameterizedTest @CsvSource({"false,LOW", "false,CRITICAL", "true,LOW", "true,CRITICAL"})
    void reclassificationReplacesCycleBeforeSelectingFinalCauseWithoutTransientEvents(boolean observed, Priority target) {
        UUID id = create(Priority.LOW, "M6");
        startManagement(id);
        makeOverdue(id, SlaType.RESOLUTION);
        if (observed) milestones.processCandidate(id, slaId(id, SlaType.RESOLUTION), Instant.now());
        Object activated = ticket(id).get("escalated_at");
        int events = escalationEvents(id);
        UUID returnId = UUID.randomUUID(); inboxIds.add(returnId);
        Instant now = Instant.now();
        external.applyUpdate(id, new UpdateTicketStatusEnvelope("1.0", returnId, "updateTicketStatus", now,
                new UpdateTicketStatusEnvelope.Producer("M6", "qa02-close"), "tickets/" + id,
                new UpdateTicketStatusRequest(id, UpdateTicketStatusType.RETURNED, "Reclasificar", null, null,
                        new UpdateTicketStatusRequest.Details(null, new UpdateTicketStatusRequest.ReturnInfo("REQUEST_TYPE_MISMATCH"), null, null),
                        new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", agent.toString()), now)));
        var proposal = reclassification.propose(id, new ProposeReclassificationRequest(type(target, "M6"), null, null), staff());
        reclassification.confirm(id, proposal.id(), staff());
        assertThat(ticket(id)).containsEntry("escalation_reason_code", target == Priority.CRITICAL ? "CRITICAL_PRIORITY" : null)
                .containsEntry("is_escalated", target == Priority.CRITICAL).containsEntry("current_priority", target.name());
        if (observed && target == Priority.CRITICAL) assertThat(ticket(id)).containsEntry("escalated_at", activated);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION' AND completed_at IS NULL AND status='RUNNING'", Integer.class, id))
                .isEqualTo(1);
        assertThat(activities(id, "SLA_BREACHED")).isEqualTo(1);
        assertThat(activities(id, "ESCALATED")).isEqualTo(observed || target == Priority.CRITICAL ? 1 : 0);
        assertThat(escalationEvents(id)).isEqualTo(events);
        var stable = ticket(id);
        int audit = db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id);
        reclassification.confirm(id, proposal.id(), staff());
        assertThat(ticket(id)).isEqualTo(stable);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id)).isEqualTo(audit);
        validateEvents(id);
    }

    @Test void completionEscalationEventFailureRollsBackTheWholeResolution() {
        UUID id = create(Priority.CRITICAL);
        startManagement(id);
        makeOverdue(id, SlaType.RESOLUTION);
        milestones.processCandidate(id, slaId(id, SlaType.RESOLUTION), Instant.now());
        var before = ticket(id);
        var cycles = db.queryForList("SELECT * FROM ticket_sla WHERE ticket_id=? ORDER BY id", id);
        int history = db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id);
        int notifications = db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=?", Integer.class, id);
        db.execute("CREATE FUNCTION qa02_fail_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.ticket_id='" + id + "'::uuid AND NEW.update_type='ESCALATION_CHANGED' THEN "
                + "RAISE EXCEPTION 'QA02 completion rollback'; END IF; RETURN NEW; END; $$");
        db.execute("CREATE TRIGGER qa02_fail_completion BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION qa02_fail_completion()");
        try {
            assertThatThrownBy(() -> perform(id, "RESOLVE")).hasMessageContaining("QA02 completion rollback");
            assertThat(ticket(id)).isEqualTo(before);
            assertThat(db.queryForList("SELECT * FROM ticket_sla WHERE ticket_id=? ORDER BY id", id)).isEqualTo(cycles);
            assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id)).isEqualTo(history);
            assertThat(db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=?", Integer.class, id)).isEqualTo(notifications);
            assertThat(db.queryForObject("SELECT count(*) FROM ticket_resolutions WHERE ticket_id=?", Integer.class, id)).isZero();
        } finally {
            db.execute("DROP TRIGGER qa02_fail_completion ON outbox_events");
            db.execute("DROP FUNCTION qa02_fail_completion()");
        }
    }

    private UUID create(Priority priority) {
        return create(priority, "M2");
    }

    private UUID create(Priority priority, String area) {
        UUID id = tickets.create(new CreateTicketRequest(type(priority, area), marker, "Verificación cierre SLA", Map.of(), null),
                identity(citizen, ModuleRole.CITIZEN), null).ticketId();
        ids.add(id);
        return id;
    }

    private Long type(Priority priority, String area) {
        Long type = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT ?,id,?,?,'REQUEST',?,?,'LOW',0,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id",
                Long.class, marker + types.size(), marker + types.size(), marker, area, priority.name());
        types.add(type);
        return type;
    }

    private void startManagement(UUID id) {
        tickets.startReview(id, staff());
        tickets.routeToArea(id, staff());
    }

    private void makeOverdue(UUID id, SlaType type) {
        db.update("UPDATE ticket_sla SET started_at=CURRENT_TIMESTAMP-INTERVAL '3 hours',near_due_at=CURRENT_TIMESTAMP-INTERVAL '2 hours',due_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE ticket_id=? AND sla_type=? AND completed_at IS NULL", id, type.name());
    }

    private void perform(UUID id, String operation) {
        switch (operation) {
            case "REVIEW" -> tickets.startReview(id, staff());
            case "RESOLVE" -> resolutions.resolveManually(id,
                    new ResolveTicketRequest(ResolutionType.REQUEST_FULFILLED, "Solicitud atendida", null), staff());
            case "CANCEL" -> {
                CancelTicketRequest request = new CancelTicketRequest();
                request.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN);
                request.setPublicMessage("Retiro del ciudadano");
                tickets.cancelTicket(id, request, identity(citizen, ModuleRole.CITIZEN));
            }
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private AuthenticatedIdentity staff() { return identity(agent, ModuleRole.AGENT); }
    private AuthenticatedIdentity identity(UUID id, ModuleRole role) { return new AuthenticatedIdentity(id.toString(), id, "QA02", "M2", role); }
    private Map<String, Object> ticket(UUID id) { return db.queryForMap("SELECT * FROM tickets WHERE id=?", id); }
    private long slaId(UUID id, SlaType type) { return db.queryForObject("SELECT id FROM ticket_sla WHERE ticket_id=? AND sla_type=?", Long.class, id, type.name()); }
    private int activities(UUID id, String action) { return db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? AND action_type=?", Integer.class, id, action); }
    private int escalationEvents(UUID id) { return db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=? AND update_type='ESCALATION_CHANGED'", Integer.class, id); }
    private void validateEvents(UUID id) {
        outbox.findAll().stream().filter(e -> e.getTicket().getId().equals(id))
                .forEach(e -> PriorityEventContractAssertions.assertFullEventValid(e.getPayload()));
    }
}
