package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reclamos.backend.dto.*;
import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.OutboxEventRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ticket.priority.scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000",
        "ticket.information-request.expiration-scan-delay=3600000", "ticket.resolution.confirmation-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc
class TicketCancellationPostgresIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired TicketCancellationRequestService requests;
    @Autowired TicketCancellationConflictService conflicts;
    @Autowired DuplicateTicketService duplicates;
    @Autowired TicketReclassificationService reclassification;
    @Autowired InformationRequestService information;
    @Autowired InformationRequestExpirationService expiration;
    @Autowired TicketStatusUpdateService external;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();
    final UUID owner = UUID.randomUUID(), agent = UUID.randomUUID();
    final String marker = "H05-" + UUID.randomUUID();
    final List<UUID> ids = new ArrayList<>();
    final List<Long> types = new ArrayList<>();
    final Set<UUID> citizens = new HashSet<>();
    final Map<UUID,CreateTicketResponse> credentials = new HashMap<>();

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?,'H05','Agent','AGENT',TRUE,now(),now(),now())", agent);
    }
    @AfterEach void cleanup() {
        for (UUID id : ids) db.update("UPDATE tickets SET main_ticket_id=NULL WHERE id=?", id);
        for (UUID id : ids) {
            for (String table : List.of("ticket_cancellation_requests", "ticket_reclassification_proposals", "information_requests",
                    "notification_logs", "outbox_events", "ticket_messages", "ticket_activities", "ticket_sla", "ticket_locations", "ticket_cancellations"))
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        for (Long type : types) db.update("DELETE FROM request_types WHERE id=?", type);
        db.update("DELETE FROM module_users WHERE citizen_id=?", agent);
        for(UUID citizen: citizens) db.update("DELETE FROM module_users WHERE citizen_id=?", citizen);
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void ordinaryCancellationAndLegacyFamilyGuard(boolean anonymous) throws Exception {
        UUID ordinary = create(anonymous, owner, "LOW");
        if (anonymous) anonymousPost("/api/tracking/actions/cancel", ordinary,
                "{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}").andExpect(status().isOk());
        else tickets.cancelTicket(ordinary, cancel(null), citizen());
        assertThat(ticketStatus(ordinary)).isEqualTo("CANCELLED");
        assertThat(scalar("SELECT cancellation_scope FROM ticket_cancellations WHERE ticket_id=?", ordinary)).isNull();
        assertThat(count("ticket_sla", ordinary, "completed_at IS NULL")).isZero();
        UUID main = family(anonymous, 1);
        if (anonymous) anonymousPost("/api/tracking/actions/cancel", main,
                "{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\"}").andExpect(status().isConflict());
        else assertThatThrownBy(() -> tickets.cancelTicket(main, cancel(null), citizen())).isInstanceOf(TicketStateConflictException.class);
        assertThat(ticketStatus(main)).isEqualTo("REGISTERED");
        assertThat(count("ticket_cancellations", main, "TRUE")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void createDoesNotMutateTicketAndHistoryRequiresOwnerAccreditation(boolean anonymous) throws Exception {
        UUID main = family(anonymous, 1); var before = ticket(main);
        UUID request;
        if (anonymous) {
            var c = credentials.get(main);
            mvc.perform(post("/api/tracking/actions/cancellation-requests").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("trackingCode", c.trackingCode(), "anonymousAccessPassword", "wrong",
                            "payload", Map.of("reasonCode", "WITHDRAWN_BY_CITIZEN", "reason", "Retirar")))))
                    .andExpect(status().isUnauthorized());
            var response = anonymousPost("/api/tracking/actions/cancellation-requests", main,
                    "{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\",\"reason\":\"Retirar\"}")
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.requesterKind").value("ANONYMOUS"))
                    .andExpect(jsonPath("$.requestedByCitizenId").doesNotExist()).andReturn();
            request = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
            mvc.perform(post("/api/tracking/actions/cancellation-requests/history").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("trackingCode",c.trackingCode(),"anonymousAccessPassword",c.generatedAnonymousAccessPassword()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(request.toString()));
            String stored = db.queryForObject("SELECT row_to_json(r)::text FROM ticket_cancellation_requests r WHERE id=?", String.class, request);
            assertThat(stored).doesNotContain(c.trackingCode(), c.generatedAnonymousAccessPassword(), "password", "tracking");
        } else {
            var r = requests.create(main, input(), citizen()); request = r.id();
            assertThat(r.requesterKind()).isEqualTo(CancellationRequesterKind.IDENTIFIED);
            mvc.perform(get(own(main)).with(authentication(auth(UUID.randomUUID(), ModuleRole.CITIZEN)))).andExpect(status().isForbidden());
            mvc.perform(get(own(main)).with(authentication(auth(owner,ModuleRole.CITIZEN)))).andExpect(status().isOk());
        }
        assertThat(ticket(main)).isEqualTo(before);
        assertThat(count("ticket_cancellations",main,"TRUE")).isZero();
        assertThatThrownBy(() -> { if (anonymous) requests.createAnonymous(main,input()); else requests.create(main,input(),citizen()); })
                .isInstanceOf(TicketStateConflictException.class);
        assertThat(count("ticket_cancellation_requests",main,"status='PENDING'")).isEqualTo(1);
        assertThat(count("ticket_activities",main,"action_type='CANCELLATION_REQUESTED'")).isEqualTo(1);
    }

    @Test void rejectIsMotivatedTerminalIdempotentAndPreservesHistory() {
        UUID main = family(false,1); var r = requests.create(main,input(),citizen());
        assertThatThrownBy(() -> requests.reject(main,r.id(),review(null," "),staff())).isInstanceOf(InvalidTicketRequestException.class);
        assertThatThrownBy(() -> requests.reject(main,r.id(),review(CancellationScope.GLOBAL,"No"),staff())).isInstanceOf(InvalidTicketRequestException.class);
        var rejected = requests.reject(main,r.id(),review(null,"El incidente sigue vigente"),staff());
        assertThat(rejected.status()).isEqualTo(CancellationRequestStatus.REJECTED); assertThat(rejected.cancellationScope()).isNull();
        assertThat(rejected.reviewedBy()).isEqualTo(agent);assertThat(rejected.decisionReason()).isEqualTo("El incidente sigue vigente");
        assertThat(requests.ownHistory(main,citizen()).getFirst().reviewedBy()).isNull();
        assertThat(requests.ownHistory(main,citizen()).getFirst().decisionReason()).isNull();
        requests.reject(main,r.id(),review(null,"El incidente sigue vigente"),staff());
        assertThat(count("ticket_activities",main,"action_type='CANCELLATION_REJECTED'")).isEqualTo(1);
        assertThatThrownBy(() -> requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todo inválido"),staff())).isInstanceOf(TicketStateConflictException.class);
        requests.create(main,input(),citizen()); assertThat(requests.ownHistory(main,citizen())).hasSize(2);
        assertThatThrownBy(() -> db.update("UPDATE ticket_cancellation_requests SET reason='alterado' WHERE id=?",r.id())).hasMessageContaining("Immutable");
    }

    @Test void globalCancelsOnlyActiveFamilyAndPreservesTerminalHistory() {
        UUID main = family(false,3); List<UUID> children = children(main); UUID historical = children.getLast();
        db.update("UPDATE tickets SET current_status='CLOSED' WHERE id=?",historical);
        var r = requests.create(main,input(),citizen());
        requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Incidente inválido"),staff());
        assertThat(ticketStatus(main)).isEqualTo("CANCELLED"); assertThat(ticketStatus(historical)).isEqualTo("CLOSED");
        for (UUID child : children.subList(0,2)) {
            assertThat(ticketStatus(child)).isEqualTo("CANCELLED"); assertThat(parent(child)).isEqualTo(main);
            assertThat(count("ticket_cancellations",child,"TRUE")).isEqualTo(1);
            assertThat(count("outbox_events",child,"update_type='CANCELLED'")).isEqualTo(1);
        }
        assertThat(count("ticket_cancellations",historical,"TRUE")).isZero();
        assertThat(scalar("SELECT cancellation_scope FROM ticket_cancellations WHERE ticket_id=?",main)).isEqualTo("GLOBAL");
        for (UUID id : ids) { assertThat(count("ticket_sla",id,"completed_at IS NULL")).isZero(); validateEvents(id); }
        var before = effects(); requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Incidente inválido"),staff());
        assertThat(effects()).isEqualTo(before);
        assertThatThrownBy(() -> requests.approve(main,r.id(),review(CancellationScope.PRINCIPAL_ONLY,"Incidente inválido"),staff()))
                .isInstanceOf(TicketStateConflictException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void principalOnlyUsesAgeAndStableUuidPreservesIdentityClassificationAndSla(boolean tie) {
        UUID main = family(false,3); List<UUID> children = children(main); UUID chosen = children.getFirst();
        if (tie) for(UUID child: children) db.update("UPDATE tickets SET created_at=? WHERE id=?",java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),child);
        else db.update("UPDATE tickets SET created_at=now()-interval '2 days' WHERE id=?",chosen);
        Map<String,Object> before = ticket(chosen); var first = db.queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='FIRST_RESPONSE'",chosen);
        var oldResolution = db.queryForList("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION'",chosen);
        var r = requests.create(main,input(),citizen());
        requests.approve(main,r.id(),review(CancellationScope.PRINCIPAL_ONLY,"Sólo este reporte"),staff());
        assertThat(ticketStatus(main)).isEqualTo("CANCELLED"); assertThat(ticketStatus(chosen)).isEqualTo("IN_REVIEW"); assertThat(parent(chosen)).isNull();
        Map<String,Object> after = ticket(chosen);
        for(String key: List.of("id","public_id","citizen_id","request_type_id","form_data","current_priority","responsible_area_id"))
            assertThat(after.get(key)).isEqualTo(before.get(key));
        assertThat(after.get("classification_finalized_at")).isEqualTo(before.get("classification_finalized_at"));
        assertThat(db.queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='FIRST_RESPONSE'",chosen)).isEqualTo(first);
        var cycles = db.queryForList("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION' ORDER BY cycle_number",chosen);
        assertThat(cycles).hasSize(oldResolution.size()+1); assertThat(cycles.getFirst()).isEqualTo(oldResolution.getFirst());
        assertThat(cycles.getLast().get("completed_at")).isNull();
        assertThat(count("outbox_events",chosen,"update_type='STATUS_CHANGED'")).isEqualTo(1);
        assertThat(count("outbox_events",chosen,"update_type IN ('ROUTED','CONTENT_UPDATED')")).isZero();
        for(UUID child: children.subList(1,children.size())) {
            assertThat(parent(child)).isEqualTo(chosen); assertThat(ticketStatus(child)).isEqualTo("DUPLICATE");
            assertThat(count("ticket_sla",child,"completed_at IS NULL")).isZero();
            assertThat(count("outbox_events",child,"update_type='DUPLICATE_LINKED'")).isEqualTo(2);
        }
        assertThatThrownBy(() -> reclassification.propose(chosen,new ProposeReclassificationRequest(type("LOW"),null,null),staff()))
                .isInstanceOf(TicketStateConflictException.class);
        assertIntegrity(); for(UUID id:ids) validateEvents(id);
    }

    @ParameterizedTest @EnumSource(value=CancellationScope.class)
    void outboxFailureRollsBackEveryFamilyEffect(CancellationScope scope) {
        UUID main = family(false,2); var r = requests.create(main,input(),citizen()); var before = effects();
        UUID failureTarget=db.queryForObject("SELECT id FROM tickets WHERE main_ticket_id=? ORDER BY created_at DESC,id DESC LIMIT 1",UUID.class,main);
        db.execute("CREATE FUNCTION h05_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ticket_id='"+failureTarget+"'::uuid AND NEW.update_type IN ('CANCELLED','DUPLICATE_LINKED') THEN RAISE EXCEPTION 'H05 rollback'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER h05_fail BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION h05_fail()");
        try { assertThatThrownBy(() -> requests.approve(main,r.id(),review(scope,"Decisión"),staff())).hasStackTraceContaining("H05 rollback"); }
        finally { db.execute("DROP TRIGGER h05_fail ON outbox_events"); db.execute("DROP FUNCTION h05_fail()"); }
        assertThat(effects()).isEqualTo(before); assertThat(ticketStatus(main)).isEqualTo("REGISTERED");
        assertThat(requests.ownHistory(main,citizen()).getFirst().status()).isEqualTo(CancellationRequestStatus.PENDING); assertIntegrity();
    }

    @ParameterizedTest @EnumSource(value=ModuleRole.class,names={"CITIZEN","AREA_RESPONSIBLE"})
    void staffPermissionsAndOwnershipAreEnforced(ModuleRole role) throws Exception {
        UUID main = family(false,1); var r=requests.create(main,input(),citizen());
        mvc.perform(get(staffBase(main)).with(authentication(auth(UUID.randomUUID(),role)))).andExpect(status().isForbidden());
        mvc.perform(post(staffBase(main)+"/"+r.id()+"/approve").with(authentication(auth(UUID.randomUUID(),role)))
                .contentType("application/json").content("{\"cancellationScope\":\"GLOBAL\",\"decisionReason\":\"Todos\"}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),identity(owner,ModuleRole.ADMIN)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> requests.staffGet(main,UUID.randomUUID(),staff())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void pendingConstraintsAndScopeConsistencyAreEnforcedByPostgres() {
        UUID main=family(false,1); var r=requests.create(main,input(),citizen());
        assertThatThrownBy(() -> db.update("INSERT INTO ticket_cancellation_requests SELECT gen_random_uuid(),ticket_id,requested_by_citizen_id,requester_kind,reason,reason_code,status,requested_at,reviewed_at,reviewed_by,cancellation_scope,decision_reason,duplicate_snapshot,requested_status_changed_at FROM ticket_cancellation_requests WHERE id=?",r.id()))
                .hasMessageContaining("uk_cancellation_request_pending");
        assertThatThrownBy(() -> db.update("UPDATE ticket_cancellation_requests SET cancellation_scope='GLOBAL' WHERE id=?",r.id())).hasMessageContaining("ck_cancellation_request_decision");
        assertThatThrownBy(() -> db.update("UPDATE ticket_cancellation_requests SET status='APPROVED',reviewed_at=now(),reviewed_by=?,cancellation_scope='GLOBAL',decision_reason='motivo' WHERE id=?",agent,r.id()))
                .hasMessageContaining("scope mismatch");
    }

    @Test void changedStateOrNewDuplicateMakesRequestStale() {
        UUID main=family(false,1); var r=requests.create(main,input(),citizen());
        UUID extra=create(false,UUID.randomUUID(),"LOW"); duplicates.link(extra,new LinkDuplicateRequest(main),staff());
        assertThatThrownBy(() -> requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),staff())).isInstanceOf(TicketStateConflictException.class);
        tickets.startReview(main,staff());
        assertThatThrownBy(() -> requests.create(main,input(),citizen())).isInstanceOf(TicketStateConflictException.class);
        assertThatThrownBy(() -> requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),staff())).isInstanceOf(TicketStateConflictException.class);
        requests.reject(main,r.id(),review(null,"Solicitud desactualizada por cambio de estado"),staff());
        assertThat(ticketStatus(main)).isEqualTo("IN_REVIEW");
    }

    @Test void directAdministrativeCancellationRequiresScopeAndTerminatesInformationAndProposal() {
        UUID main=family(false,1); tickets.startReview(main,staff());
        information.requestInformation(main,new CreateInformationRequest("Necesitamos datos",null),staff());
        assertThatThrownBy(() -> tickets.cancelTicket(main,cancel(null),staff())).isInstanceOf(InvalidTicketRequestException.class);
        tickets.cancelTicket(main,cancel(CancellationScope.GLOBAL),staff());
        assertThat(count("information_requests",main,"status='PENDING'")).isZero(); assertThat(ticketStatus(main)).isEqualTo("CANCELLED"); assertIntegrity();
    }

    @Test void concurrentApprovalsAreIdempotent() throws Exception {
        UUID main=family(false,2); var r=requests.create(main,input(),citizen());
        race(() -> requests.approve(main,r.id(),review(CancellationScope.PRINCIPAL_ONLY,"Sólo reporte"),staff()),
                () -> requests.approve(main,r.id(),review(CancellationScope.PRINCIPAL_ONLY,"Sólo reporte"),staff()));
        assertThat(count("ticket_activities",main,"action_type='CANCELLATION_APPROVED'")).isEqualTo(1);
        assertThat(count("ticket_cancellations",main,"TRUE")).isEqualTo(1); assertIntegrity();
    }

    @Test void childLockContentionFailsFastWithoutDeadlockAndRetrySucceeds() throws Exception {
        UUID main=family(false,1); UUID child=children(main).getFirst(); var r=requests.create(main,input(),citizen());
        try(Connection blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM tickets WHERE id=? FOR UPDATE")) { statement.setObject(1,child); statement.executeQuery().close(); }
            try(var pool=Executors.newSingleThreadExecutor()) {
                var attempt=pool.submit(() -> { try { requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),staff()); return false; }
                    catch(org.springframework.dao.PessimisticLockingFailureException e) { return true; } });
                assertThat(attempt.get(10,TimeUnit.SECONDS)).isTrue();
            }
            blocker.rollback();
        }
        assertThat(ticketStatus(main)).isEqualTo("REGISTERED");
        requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),staff()); assertIntegrity();
    }

    @Test void cancellationVersusNewLinkAndStateTransitionPreservesIntegrity() throws Exception {
        UUID main=family(false,1); UUID extra=create(false,UUID.randomUUID(),"LOW"); var r=requests.create(main,input(),citizen());
        race(() -> allowConflict(() -> requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),staff())),
                () -> allowConflict(() -> duplicates.link(extra,new LinkDuplicateRequest(main),staff())));
        assertIntegrity();
        UUID second=family(false,1); var s=requests.create(second,input(),citizen());
        race(() -> allowConflict(() -> requests.approve(second,s.id(),review(CancellationScope.GLOBAL,"Todos"),staff())),
                () -> allowConflict(() -> tickets.startReview(second,staff()))); assertIntegrity();
    }

    @Test void newEndpointsEnforceValidationAndReturnTheActualPendingDecision() throws Exception {
        UUID main=family(false,1);
        mvc.perform(post(own(main)).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(own(main)).with(authentication(auth(owner,ModuleRole.CITIZEN))).contentType("application/json")
                .content("{\"reasonCode\":\"OTHER\",\"reason\":\" \"}")).andExpect(status().isBadRequest());
        var response=mvc.perform(post(own(main)).with(authentication(auth(owner,ModuleRole.CITIZEN))).contentType("application/json")
                .content("{\"reasonCode\":\"WITHDRAWN_BY_CITIZEN\",\"reason\":\"Retiro\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        String request=json.readTree(response.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get(staffBase(main)).with(authentication(auth(agent,ModuleRole.AGENT)))).andExpect(status().isOk());
        mvc.perform(get(staffBase(main)+"/"+request).with(authentication(auth(agent,ModuleRole.ADMIN)))).andExpect(status().isOk());
        mvc.perform(post(staffBase(main)+"/"+request+"/approve").with(authentication(auth(agent,ModuleRole.AGENT)))
                .contentType("application/json").content("{\"decisionReason\":\"Motivo\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(staffBase(main)+"/"+request+"/reject").with(authentication(auth(agent,ModuleRole.ADMIN)))
                .contentType("application/json").content("{\"decisionReason\":\"Incidente válido\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        var next=requests.create(main,input(),citizen());
        mvc.perform(post(staffBase(main)+"/"+next.id()+"/approve").with(authentication(auth(agent,ModuleRole.ADMIN)))
                .contentType("application/json").content("{\"cancellationScope\":\"GLOBAL\",\"decisionReason\":\"Incidente inválido\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get(own(main)).with(authentication(auth(owner,ModuleRole.CITIZEN)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @ParameterizedTest @EnumSource(CancellationScope.class)
    void legacyPendingInformationOnChildCannotSurviveFamilyCancellation(CancellationScope scope) {
        UUID main=create(false,owner,"LOW"), child=create(false,UUID.randomUUID(),"LOW");
        tickets.startReview(child,staff());
        information.requestInformation(child,new CreateInformationRequest("Datos del hijo",null),staff());
        duplicates.link(child,new LinkDuplicateRequest(main),staff());
        assertThat(count("information_requests",child,"status='PENDING'")).isEqualTo(1);
        var r=requests.create(main,input(),citizen());requests.approve(main,r.id(),review(scope,"Decisión"),staff());
        assertThat(count("information_requests",child,"status='PENDING'")).isZero();
        assertThat(count("information_requests",child,"status='CANCELLED'")).isEqualTo(1);
        assertIntegrity();
    }

    @Test void promotionReconcilesOldBreachAndCurrentCriticalPriorityWithoutRestartingFirstResponse() {
        UUID main=family(false,1), child=children(main).getFirst();
        db.update("UPDATE tickets SET current_priority='CRITICAL',is_escalated=TRUE,escalation_reason_code='SLA_BREACHED',escalated_at=now()-interval '1 hour' WHERE id=?",child);
        var first=db.queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='FIRST_RESPONSE'",child);
        var r=requests.create(main,input(),citizen());requests.approve(main,r.id(),review(CancellationScope.PRINCIPAL_ONLY,"Sólo reporte"),staff());
        assertThat(scalar("SELECT escalation_reason_code FROM tickets WHERE id=?",child)).isEqualTo("CRITICAL_PRIORITY");
        assertThat(scalar("SELECT current_priority FROM tickets WHERE id=?",child)).isEqualTo("CRITICAL");
        assertThat(db.queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='FIRST_RESPONSE'",child)).isEqualTo(first);
        assertThat(db.queryForObject("SELECT s.policy_id=p.id FROM ticket_sla s JOIN sla_policies p ON p.priority='CRITICAL' AND p.sla_type='RESOLUTION' WHERE s.ticket_id=? AND s.completed_at IS NULL",Boolean.class,child)).isTrue();
        validateEvents(child);
    }

    @ParameterizedTest @EnumSource(CancellationScope.class)
    void anonymousFamilyPublishesOnlyIdentifiedProjectionChanges(CancellationScope scope) {
        UUID main=create(true,owner,"LOW"), child=create(true,owner,"LOW");duplicates.link(child,new LinkDuplicateRequest(main),staff());
        int prior=count("outbox_events",child,"TRUE");
        var r=requests.createAnonymous(main,input());requests.approve(main,r.id(),review(scope,"Decisión"),staff());
        assertThat(count("outbox_events",main,"TRUE")).isZero();assertThat(count("outbox_events",child,"TRUE")).isEqualTo(prior);
        assertIntegrity();
    }

    @Test void staffCannotModifyOwnChildAsPartOfAnotherCitizensFamily() {
        UUID main=create(false,owner,"LOW"),child=create(false,agent,"LOW");
        duplicates.link(child,new LinkDuplicateRequest(main),identity(owner,ModuleRole.ADMIN));
        var r=requests.create(main,input(),citizen());
        assertThatThrownBy(()->requests.approve(main,r.id(),review(CancellationScope.GLOBAL,"Todos"),staff())).isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThat(ticketStatus(main)).isEqualTo("REGISTERED");assertThat(ticketStatus(child)).isEqualTo("DUPLICATE");
    }

    @Test void timeoutExpiresRequestResumesSlaAndExternalRejectionRecordsOnlyConflict() {
        UUID main=family(false,1);tickets.startReview(main,staff());
        information.requestInformation(main,new CreateInformationRequest("Datos",null),staff());
        UUID ir=db.queryForObject("SELECT id FROM information_requests WHERE ticket_id=?",UUID.class,main);
        db.update("UPDATE information_requests SET requested_at=now()-interval '1 day',due_at=now()-interval '1 second' WHERE id=?",ir);
        expiration.expireIfDue(ir,main,Instant.now());
        assertThat(ticketStatus(main)).isEqualTo("IN_REVIEW");
        assertThat(count("information_requests",main,"status='EXPIRED'")).isEqualTo(1);
        assertThat(count("ticket_sla",main,"sla_type='RESOLUTION' AND completed_at IS NULL AND paused_at IS NULL")).isEqualTo(1);
        assertThat(count("ticket_cancellations",main,"TRUE")).isZero();
        assertThat(conflicts.list(main,staff())).hasSize(1);
        expiration.expireIfDue(ir,main,Instant.now());
        assertThat(conflicts.list(main,staff())).hasSize(1);
        assertThatThrownBy(()->information.answerInformation(main,new AnswerInformationRequest("Tardía"),citizen())).isInstanceOf(InformationRequestExpiredException.class);
        UUID routed=family(false,1);tickets.startReview(routed,staff());tickets.routeToArea(routed,staff());
        Instant now=Instant.now();UUID event=UUID.randomUUID();
        var rejected=new UpdateTicketStatusEnvelope("1.0",event,"updateTicketStatus",now,
                new UpdateTicketStatusEnvelope.Producer("M6","h05-test"),"tickets/"+routed,
                new UpdateTicketStatusRequest(routed,UpdateTicketStatusType.REJECTED,"Rechazo",null,null,
                        new UpdateTicketStatusRequest.Details(null,null,null,new UpdateTicketStatusRequest.Cancellation("REJECTED_BY_AREA")),
                        new UpdateTicketStatusRequest.Actor("EXTERNAL_USER",UUID.randomUUID().toString()),now));
        assertThatThrownBy(()->external.applyUpdate(routed,rejected)).isInstanceOf(TicketStateConflictException.class);
        assertThat(ticketStatus(routed)).isEqualTo("ROUTED");
        assertThat(db.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?",Integer.class,event)).isZero();assertIntegrity();
        assertThat(conflicts.list(routed,staff())).hasSize(1);
        assertThatThrownBy(()->external.applyUpdate(routed,rejected)).isInstanceOf(TicketStateConflictException.class);
        assertThat(conflicts.list(routed,staff())).hasSize(1);
        assertThat(count("ticket_messages",routed,"TRUE")).isZero();
        assertThat(count("outbox_events",routed,"update_type='CANCELLED'")).isZero();
    }

    @ParameterizedTest @CsvSource({"INFO_TIMEOUT,GLOBAL","INFO_TIMEOUT,PRINCIPAL_ONLY","EXTERNAL_REJECTED,GLOBAL","EXTERNAL_REJECTED,PRINCIPAL_ONLY"})
    void administrativeConflictReviewAppliesBothScopesAtomically(String origin,CancellationScope scope) throws Exception {
        UUID main=family(false,2);createConflict(main,origin);
        var conflict=conflicts.list(main,staff()).getFirst();
        assertThat(conflict.origin()).isEqualTo(origin);
        var decision=new ResolveCancellationConflictRequest(CancellationRequestStatus.APPROVED,scope,"Decisión del incidente","Mensaje público");
        mvc.perform(get(conflictBase(main)).with(authentication(auth(agent,ModuleRole.AGENT)))).andExpect(status().isOk());
        mvc.perform(post(conflictBase(main)+"/"+conflict.id()+"/resolve").with(authentication(auth(agent,ModuleRole.ADMIN)))
                .contentType("application/json").content(json.writeValueAsString(decision)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(ticketStatus(main)).isEqualTo("CANCELLED");assertThat(scalar("SELECT cancellation_scope FROM ticket_cancellations WHERE ticket_id=?",main)).isEqualTo(scope.name());
        var before=effects();conflicts.resolve(main,conflict.id(),decision,staff());assertThat(effects()).isEqualTo(before);
        if(scope==CancellationScope.PRINCIPAL_ONLY) assertThat(ids.stream().filter(id->!id.equals(main)).filter(id->"IN_REVIEW".equals(ticketStatus(id))).count()).isEqualTo(1);
        else for(UUID id:ids) assertThat(ticketStatus(id)).isEqualTo("CANCELLED");
        for(UUID id:ids) validateEvents(id);assertIntegrity();
    }

    @Test void conflictReviewRejectsStaleDecisionsAndEnforcesSecurityAndRationale() throws Exception {
        UUID main=family(false,1);createConflict(main,"EXTERNAL_REJECTED");var conflict=conflicts.list(main,staff()).getFirst();
        mvc.perform(get(conflictBase(main)).with(authentication(auth(owner,ModuleRole.CITIZEN)))).andExpect(status().isForbidden());
        mvc.perform(post(conflictBase(main)+"/"+conflict.id()+"/resolve").with(authentication(auth(agent,ModuleRole.AREA_RESPONSIBLE)))
                .contentType("application/json").content("{\"decision\":\"APPROVED\",\"cancellationScope\":\"GLOBAL\",\"decisionReason\":\"Todos\"}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(()->conflicts.resolve(main,conflict.id(),new ResolveCancellationConflictRequest(CancellationRequestStatus.APPROVED,null,"Motivo",null),staff())).isInstanceOf(InvalidTicketRequestException.class);
        UUID extra=create(false,UUID.randomUUID(),"LOW");duplicates.link(extra,new LinkDuplicateRequest(main),staff());
        assertThatThrownBy(()->conflicts.resolve(main,conflict.id(),new ResolveCancellationConflictRequest(CancellationRequestStatus.APPROVED,CancellationScope.GLOBAL,"Motivo",null),staff())).isInstanceOf(TicketStateConflictException.class);
        var rejection=new ResolveCancellationConflictRequest(CancellationRequestStatus.REJECTED,null,"El incidente continúa vigente",null);
        var before=ticket(main);assertThat(conflicts.resolve(main,conflict.id(),rejection,staff()).status()).isEqualTo(CancellationRequestStatus.REJECTED);
        conflicts.resolve(main,conflict.id(),rejection,staff());assertThat(ticket(main)).isEqualTo(before);
        assertThatThrownBy(()->conflicts.resolve(main,conflict.id(),new ResolveCancellationConflictRequest(CancellationRequestStatus.APPROVED,CancellationScope.GLOBAL,"Motivo",null),staff())).isInstanceOf(TicketStateConflictException.class);
    }

    void createConflict(UUID main,String origin) {
        tickets.startReview(main,staff());
        if(origin.equals("INFO_TIMEOUT")) {
            information.requestInformation(main,new CreateInformationRequest("Datos",null),staff());
            UUID ir=db.queryForObject("SELECT id FROM information_requests WHERE ticket_id=?",UUID.class,main);
            db.update("UPDATE information_requests SET requested_at=now()-interval '1 day',due_at=now()-interval '1 second' WHERE id=?",ir);
            expiration.expireIfDue(ir,main,Instant.now());
        } else {
            tickets.routeToArea(main,staff());Instant now=Instant.now();UUID event=UUID.randomUUID();
            var rejected=new UpdateTicketStatusEnvelope("1.0",event,"updateTicketStatus",now,new UpdateTicketStatusEnvelope.Producer("M6","h05-test"),"tickets/"+main,
                    new UpdateTicketStatusRequest(main,UpdateTicketStatusType.REJECTED,"Mensaje externo","Nota privada externa",null,
                            new UpdateTicketStatusRequest.Details(null,null,null,new UpdateTicketStatusRequest.Cancellation("REJECTED_BY_AREA")),
                            new UpdateTicketStatusRequest.Actor("EXTERNAL_USER",UUID.randomUUID().toString()),now));
            assertThatThrownBy(()->external.applyUpdate(main,rejected)).isInstanceOf(CancellationScopeReviewRequiredException.class);
            assertThat(conflicts.list(main,staff()).getFirst().internalMessage()).isEqualTo("Nota privada externa");
        }
    }
    String conflictBase(UUID id) {return "/api/staff/tickets/"+id+"/cancellation-conflicts";}

    @Test void administrativeCancellationRacesWithNewReclassificationProposalWithoutLeavingOpenAttempts() throws Exception {
        UUID main=family(false,1);tickets.startReview(main,staff());Long target=type("LOW");
        race(()->tickets.cancelTicket(main,cancel(CancellationScope.GLOBAL),staff()),
                ()->allowConflict(()->reclassification.propose(main,new ProposeReclassificationRequest(target,null,null),staff())));
        assertThat(ticketStatus(main)).isEqualTo("CANCELLED");
        assertThat(count("ticket_reclassification_proposals",main,"status IN ('PROPOSED','READY','WAITING_INFORMATION')")).isZero();assertIntegrity();
    }

    @Test void promotionRacesWithChildExpirationWithoutReactivatingInformation() throws Exception {
        UUID main=create(false,owner,"LOW"),child=create(false,UUID.randomUUID(),"LOW");tickets.startReview(child,staff());
        information.requestInformation(child,new CreateInformationRequest("Datos",null),staff());
        UUID ir=db.queryForObject("SELECT id FROM information_requests WHERE ticket_id=?",UUID.class,child);
        db.update("UPDATE information_requests SET requested_at=now()-interval '1 day',due_at=now()-interval '1 second' WHERE id=?",ir);
        duplicates.link(child,new LinkDuplicateRequest(main),staff());var request=requests.create(main,input(),citizen());
        race(()->allowConflict(()->requests.approve(main,request.id(),review(CancellationScope.PRINCIPAL_ONLY,"Sólo reporte"),staff())),
                ()->expiration.expireIfDue(ir,child,Instant.now()));
        if(ticketStatus(main).equals("REGISTERED")) requests.approve(main,request.id(),review(CancellationScope.PRINCIPAL_ONLY,"Sólo reporte"),staff());
        assertThat(ticketStatus(child)).isEqualTo("IN_REVIEW");assertThat(parent(child)).isNull();
        assertThat(count("information_requests",child,"status='CANCELLED'")).isEqualTo(1);
        assertThat(count("ticket_sla",child,"sla_type='RESOLUTION' AND completed_at IS NULL")).isEqualTo(1);assertIntegrity();
    }

    UUID family(boolean anonymous,int size) {
        UUID main=create(anonymous,owner,"LOW");
        for(int i=0;i<size;i++) { UUID child=create(false,UUID.randomUUID(),"LOW"); duplicates.link(child,new LinkDuplicateRequest(main),staff()); }
        return main;
    }
    UUID create(boolean anonymous,UUID citizen,String priority) {
        if (!anonymous && !citizen.equals(agent) && citizens.add(citizen)) db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) VALUES (?,'H05','Citizen','CITIZEN',TRUE,now(),now(),now())",citizen);
        var c=tickets.create(new CreateTicketRequest(type(priority),"H05","Cancelación",Map.of(),null),anonymous?null:identity(citizen,ModuleRole.CITIZEN),null);
        ids.add(c.ticketId()); credentials.put(c.ticketId(),c); return c.ticketId();
    }
    Long type(String priority) {
        String code=marker+"-"+types.size();
        Long type=db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) SELECT ?,id,?,?,'REQUEST','M6',?,'LOW',0,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id",Long.class,code,code,marker,priority);
        types.add(type); return type;
    }
    CreateCancellationRequest input() { return new CreateCancellationRequest(CancellationReasonCode.WITHDRAWN_BY_CITIZEN,"Retirar reporte"); }
    ReviewCancellationRequest review(CancellationScope scope,String reason) { return new ReviewCancellationRequest(scope,reason,"Cancelación revisada"); }
    CancelTicketRequest cancel(CancellationScope scope) { var r=new CancelTicketRequest(); r.setReasonCode(CancellationReasonCode.WITHDRAWN_BY_CITIZEN); r.setCancellationScope(scope); r.setDecisionReason("Decisión administrativa"); return r; }
    AuthenticatedIdentity citizen() { return identity(owner,ModuleRole.CITIZEN); }
    AuthenticatedIdentity staff() { return identity(agent,ModuleRole.AGENT); }
    AuthenticatedIdentity identity(UUID id,ModuleRole role) { return new AuthenticatedIdentity(id.toString(),id,"H05","M6",role); }
    UsernamePasswordAuthenticationToken auth(UUID id,ModuleRole role) { return new UsernamePasswordAuthenticationToken(identity(id,role),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))); }
    String own(UUID id) { return "/api/tickets/"+id+"/cancellation-requests"; }
    String staffBase(UUID id) { return "/api/staff/tickets/"+id+"/cancellation-requests"; }
    org.springframework.test.web.servlet.ResultActions anonymousPost(String path,UUID id,String payload) throws Exception {
        var c=credentials.get(id);
        return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsString(Map.of("trackingCode",c.trackingCode(),"anonymousAccessPassword",c.generatedAnonymousAccessPassword(),"payload",json.readTree(payload)))));
    }
    String scalar(String sql,UUID id) { return db.queryForObject(sql,String.class,id); }
    String ticketStatus(UUID id) { return scalar("SELECT current_status FROM tickets WHERE id=?",id); }
    UUID parent(UUID id) { return db.queryForObject("SELECT main_ticket_id FROM tickets WHERE id=?",UUID.class,id); }
    List<UUID> children(UUID id) { return db.queryForList("SELECT id FROM tickets WHERE main_ticket_id=? AND current_status='DUPLICATE' ORDER BY id",UUID.class,id); }
    Map<String,Object> ticket(UUID id) { return db.queryForMap("SELECT id,public_id,citizen_id,request_type_id,form_data::text,current_priority,responsible_area_id,main_ticket_id,current_status,classification_finalized_at FROM tickets WHERE id=?",id); }
    int count(String table,UUID id,String predicate) { return db.queryForObject("SELECT count(*) FROM "+table+" WHERE ticket_id=? AND "+predicate,Integer.class,id); }
    List<Map<String,Object>> effects() { return ids.stream().map(id -> db.queryForMap("SELECT current_status,main_ticket_id,(SELECT count(*) FROM ticket_activities WHERE ticket_id=t.id) activities,(SELECT count(*) FROM outbox_events WHERE ticket_id=t.id) events,(SELECT count(*) FROM ticket_cancellations WHERE ticket_id=t.id) cancellations,(SELECT count(*) FROM ticket_sla WHERE ticket_id=t.id AND completed_at IS NULL) active_sla FROM tickets t WHERE id=?",id)).toList(); }
    void validateEvents(UUID id) { outbox.findAll().stream().filter(e->e.getTicket().getId().equals(id)).forEach(e->{ PriorityEventContractAssertions.assertValid(e.getPayload()); PriorityEventContractAssertions.assertFullEventValid(e.getPayload()); }); }
    void assertIntegrity() { assertThat(db.queryForObject("SELECT count(*) FROM tickets c JOIN tickets p ON p.id=c.main_ticket_id WHERE c.current_status='DUPLICATE' AND (p.current_status IN ('CANCELLED','CLOSED','DUPLICATE') OR p.main_ticket_id IS NOT NULL)",Integer.class)).isZero(); }
    void allowConflict(Runnable action) { try { action.run(); } catch(TicketStateConflictException|org.springframework.dao.PessimisticLockingFailureException expected) {} }
    void race(Runnable a,Runnable b) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) { var first=pool.submit(()->{ await(start);a.run(); });var second=pool.submit(()->{ await(start);b.run(); });start.countDown();first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS); }
    }
    void await(CountDownLatch latch) { try { if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);} }
}
