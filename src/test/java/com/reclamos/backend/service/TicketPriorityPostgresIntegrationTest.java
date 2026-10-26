package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"ticket.priority.scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000",
        "ticket.information-request.expiration-scan-delay=3600000", "ticket.resolution.confirmation-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"})
@ActiveProfiles("dev")
class TicketPriorityPostgresIntegrationTest {
    @Autowired TicketService creation;
    @Autowired TicketReclassificationService reclassification;
    @Autowired TicketPriorityService priorities;
    @Autowired TicketSlaMilestoneService milestones;
    @Autowired TicketSlaService lifecycle;
    @Autowired TicketRepository tickets;
    @Autowired TicketSlaRepository slas;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    private final List<UUID> ids=new ArrayList<>();
    private final List<Long> types=new ArrayList<>();
    private final UUID citizen=UUID.randomUUID(), agent=UUID.randomUUID(), neighborhood=UUID.randomUUID();
    private final String marker="H0708-"+UUID.randomUUID();

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()",String.class)).startsWith("PostgreSQL");
        db.update("INSERT INTO neighborhood(id,name,population) VALUES (?,?,10000)",neighborhood,marker);
        for(UUID id:List.of(citizen,agent)) db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                +"VALUES (?,'H07','H08',?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,id.equals(agent)?"AGENT":"CITIZEN");
    }
    @AfterEach void cleanup() {
        for(UUID id:ids) {
            db.update("DELETE FROM ticket_reclassification_proposals WHERE ticket_id=?",id);
            db.update("UPDATE tickets SET main_ticket_id=NULL WHERE id=?",id);
            for(String table:List.of("notification_logs","outbox_events","ticket_activities","ticket_sla","ticket_locations"))
                db.update("DELETE FROM "+table+" WHERE ticket_id=?",id);
            db.update("DELETE FROM tickets WHERE id=?",id);
        }
        for(Long id:types) db.update("DELETE FROM request_types WHERE id=?",id);
        db.update("DELETE FROM neighborhood WHERE id=?",neighborhood);
        for(UUID id:List.of(citizen,agent)) db.update("DELETE FROM module_users WHERE citizen_id=?",id);
    }

    @ParameterizedTest @CsvSource({"false,0,MEDIUM","true,0,MEDIUM","false,.1,HIGH","true,.1,HIGH"})
    void initialPriorityUsesAffectedCountBeforeBothSlaCycles(boolean anonymous,String factor,String expected) {
        UUID id=create(anonymous,type("MEDIUM","LOW",factor));
        assertThat(ticket(id)).containsEntry("current_priority",expected).containsEntry("current_status","REGISTERED");
        assertThat(db.queryForList("SELECT p.priority FROM ticket_sla s JOIN sla_policies p ON p.id=s.policy_id WHERE ticket_id=?",
                String.class,id)).containsExactlyInAnyOrder(expected,expected);
        assertThat(events(id,"ESCALATION_CHANGED")).isZero();
        validateEvents(id);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lowRiskCriticalMinimumRequiresNoEvidenceAndActivatesOnlyInitialSnapshot(boolean anonymous) {
        UUID id=create(anonymous,type("LOW","CRITICAL","0"));
        assertThat(ticket(id)).containsEntry("current_priority","CRITICAL").containsEntry("is_escalated",true)
                .containsEntry("escalation_reason_code","CRITICAL_PRIORITY");
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(events(id,"ESCALATION_CHANGED")).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=?",Integer.class,id))
                .isEqualTo(anonymous?0:1);
        validateEvents(id);
    }

    @Test void periodicIncreaseIsIdempotentDoesNotTouchDeadlinesOrPublishPriorityEvent() {
        UUID id=create(false,type("MEDIUM","LOW","1"));
        List<Map<String,Object>> before=cycles(id);
        // Catalog population/factor changes must not replace the persisted ticket estimate during a scan.
        db.update("UPDATE request_types SET affected_population_factor=0 WHERE id=(SELECT request_type_id FROM tickets WHERE id=?)",id);
        db.update("UPDATE neighborhood SET population=0 WHERE id=?",neighborhood);
        Instant now=resolutionDue(id).plusSeconds(1);
        priorities.processCandidate(id,now); priorities.processCandidate(id,now.plusSeconds(10));
        assertThat(ticket(id)).containsEntry("current_priority","CRITICAL").containsEntry("current_status","REGISTERED");
        assertThat(cycles(id)).isEqualTo(before);
        assertThat(activities(id,"PRIORITY_CHANGED")).isEqualTo(1);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(events(id,"PRIORITY_CHANGED")).isZero();
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(1);
        assertThat(db.queryForMap("SELECT previous_priority,new_priority FROM ticket_activities WHERE ticket_id=? AND action_type='PRIORITY_CHANGED'",id))
                .containsEntry("previous_priority","HIGH").containsEntry("new_priority","CRITICAL");
        validateEvents(id);
    }

    @Test void existingHighPriorityNeverFallsAndNoopDoesNotWrite() {
        UUID id=create(false,type("LOW","LOW","0"));
        db.update("UPDATE tickets SET current_priority='HIGH' WHERE id=?",id);
        Map<String,Object> before=ticket(id); var history=history(id);
        priorities.processCandidate(id,Instant.now());
        assertThat(ticket(id)).isEqualTo(before); assertThat(history(id)).isEqualTo(history);
    }

    @ParameterizedTest @EnumSource(value=TicketStatus.class,names={"DUPLICATE","RESOLVED","CLOSED","CANCELLED"})
    void excludesTerminalAndDuplicateStatesEvenForStaleCandidates(TicketStatus status) {
        UUID id=create(false,type("MEDIUM","LOW","1"));
        if(status==TicketStatus.DUPLICATE) {
            UUID main=create(false,type("LOW","LOW","0"));
            db.update("UPDATE tickets SET current_status=?,main_ticket_id=? WHERE id=?",status.name(),main,id);
        } else db.update("UPDATE tickets SET current_status=? WHERE id=?",status.name(),id);
        var before=ticket(id); var history=history(id);
        priorities.processCandidate(id,resolutionDue(id).plusSeconds(1));
        assertThat(ticket(id)).isEqualTo(before); assertThat(history(id)).isEqualTo(history);
    }

    @Test void pendingInformationUsesFrozenAge() {
        UUID id=create(false,type("MEDIUM","LOW","1"));
        Instant start=db.queryForObject("SELECT started_at FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION'",java.sql.Timestamp.class,id).toInstant();
        tx.executeWithoutResult(s -> {
            Ticket t=tickets.findByIdForUpdate(id).orElseThrow();
            t.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
            lifecycle.pauseActiveResolutionCycle(t,start);
        });
        priorities.processCandidate(id,resolutionDue(id).plusSeconds(10000));
        assertThat(ticket(id)).containsEntry("current_priority","HIGH");
        assertThat(activities(id,"PRIORITY_CHANGED")).isZero();
    }

    @ParameterizedTest @EnumSource(SlaType.class)
    void criticalBreachUpdatesReasonPreservesDateAndRecordsMilestoneOnce(SlaType type) {
        UUID id=create(false,type("LOW","CRITICAL","0"));
        Object activated=ticket(id).get("escalated_at");
        long slaId=db.queryForObject("SELECT id FROM ticket_sla WHERE ticket_id=? AND sla_type=?",Long.class,id,type.name());
        Instant due=db.queryForObject("SELECT due_at FROM ticket_sla WHERE id=?",java.sql.Timestamp.class,slaId).toInstant();
        var beforeCycles=cycles(id);
        milestones.processCandidate(id,slaId,due); milestones.processCandidate(id,slaId,due.plusSeconds(1));
        priorities.processCandidate(id,due);
        assertThat(ticket(id)).containsEntry("escalation_reason_code","SLA_BREACHED").containsEntry("escalated_at",activated);
        assertThat(activities(id,"SLA_BREACHED")).isEqualTo(1);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(1);
        assertThat(ticket(id)).containsEntry("current_status","REGISTERED").containsEntry("current_priority","CRITICAL");
        var afterCycles=cycles(id);
        for(int i=0;i<beforeCycles.size();i++) {
            assertThat(afterCycles.get(i)).containsEntry("due_at",beforeCycles.get(i).get("due_at"))
                    .containsEntry("near_due_at",beforeCycles.get(i).get("near_due_at"))
                    .containsEntry("started_at",beforeCycles.get(i).get("started_at"))
                    .containsEntry("policy_id",beforeCycles.get(i).get("policy_id"));
        }
        assertThat(db.queryForMap("SELECT metadata->>'previousReasonCode' AS previous,metadata->>'reasonCode' AS next "
                + "FROM ticket_activities WHERE ticket_id=? AND action_type='STATE_CHANGED'",id))
                .containsEntry("previous","CRITICAL_PRIORITY").containsEntry("next","SLA_BREACHED");
        validateEvents(id);
    }

    @ParameterizedTest @EnumSource(SlaType.class)
    void manualRemainsEffectiveWhenCriticalAndSlaBreachCoexist(SlaType type) {
        UUID id=create(false,type("LOW","CRITICAL","0"));
        db.update("UPDATE tickets SET escalation_reason_code='MANUAL' WHERE id=?",id);
        Object activated=ticket(id).get("escalated_at");
        long slaId=db.queryForObject("SELECT id FROM ticket_sla WHERE ticket_id=? AND sla_type=?",Long.class,id,type.name());
        Instant due=db.queryForObject("SELECT due_at FROM ticket_sla WHERE id=?",java.sql.Timestamp.class,slaId).toInstant();
        milestones.processCandidate(id,slaId,due);
        priorities.processCandidate(id,due);
        milestones.processCandidate(id,slaId,due.plusSeconds(1));
        assertThat(ticket(id)).containsEntry("escalation_reason_code","MANUAL").containsEntry("escalated_at",activated);
        assertThat(activities(id,"SLA_BREACHED")).isEqualTo(1);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(events(id,"ESCALATION_CHANGED")).isZero();
        validateEvents(id);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicitManualWithdrawalAtEntityBoundaryKeepsContinuousActivation(boolean breached) {
        UUID id=create(false,type("LOW","CRITICAL","0"));
        db.update("UPDATE tickets SET escalation_reason_code='MANUAL' WHERE id=?",id);
        if(breached) db.update("UPDATE ticket_sla SET status='BREACHED' WHERE ticket_id=? AND sla_type='RESOLUTION'",id);
        Object activated=ticket(id).get("escalated_at");
        // There is no manual command/API in this backend. Exercise its domain boundary under the real lock.
        tx.executeWithoutResult(s -> {
            Ticket t=tickets.findByIdForUpdate(id).orElseThrow();
            t.setEscalationReasonCode(null);
            milestones.reconcileEscalation(t,Instant.now(),true);
        });
        assertThat(ticket(id)).containsEntry("escalation_reason_code",breached?"SLA_BREACHED":"CRITICAL_PRIORITY")
                .containsEntry("escalated_at",activated).containsEntry("is_escalated",true);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(activities(id,"STATE_CHANGED")).isEqualTo(1);
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(1);
        validateEvents(id);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bothSchedulerOrdersConvergeAndPublishOnlyRealChanges(boolean priorityFirst) {
        UUID id=create(false,type("MEDIUM","LOW","1"));
        Instant due=resolutionDue(id); long slaId=resolutionId(id);
        if(priorityFirst) priorities.processCandidate(id,due);
        milestones.processCandidate(id,slaId,due);
        priorities.processCandidate(id,due);
        var stable=ticket(id); var audit=history(id);
        milestones.processCandidate(id,slaId,due.plusSeconds(1));
        priorities.processCandidate(id,due.plusSeconds(1));
        assertThat(ticket(id)).isEqualTo(stable).containsEntry("escalation_reason_code","SLA_BREACHED");
        assertThat(history(id)).isEqualTo(audit);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(activities(id,"SLA_BREACHED")).isEqualTo(1);
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(priorityFirst?2:1);
        validateEvents(id);
    }

    @ParameterizedTest @ValueSource(strings={"LOW","CRITICAL"})
    void completingBothBreachedCyclesDiscardsOnlyHistoricalCausesOnReconciliation(String priority) {
        UUID id=create(false,type("LOW",priority,"0"));
        Instant now=resolutionDue(id).plusSeconds(1);
        for(Map<String,Object> cycle:cycles(id)) milestones.processCandidate(id,((Number)cycle.get("id")).longValue(),now);
        Object activated=ticket(id).get("escalated_at");
        assertThat(ticket(id)).containsEntry("escalation_reason_code","SLA_BREACHED");
        int published=events(id,"ESCALATION_CHANGED");
        tx.executeWithoutResult(s -> {
            Ticket t=tickets.findByIdForUpdate(id).orElseThrow();
            lifecycle.completeFirstResponseCycle(t,now);
            milestones.reconcileEscalation(t,now,true);
        });
        assertThat(ticket(id)).containsEntry("escalation_reason_code","SLA_BREACHED").containsEntry("escalated_at",activated);
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(published);
        tx.executeWithoutResult(s -> {
            Ticket t=tickets.findByIdForUpdate(id).orElseThrow();
            lifecycle.completeActiveResolutionCycle(t,now);
            milestones.reconcileEscalation(t,now,true);
        });
        assertThat(ticket(id)).containsEntry("escalation_reason_code",priority.equals("CRITICAL")?"CRITICAL_PRIORITY":null)
                .containsEntry("escalated_at",priority.equals("CRITICAL")?activated:null)
                .containsEntry("current_priority",priority).containsEntry("current_status","REGISTERED");
        assertThat(db.queryForList("SELECT status FROM ticket_sla WHERE ticket_id=? AND completed_at IS NOT NULL",String.class,id))
                .containsExactly("BREACHED","BREACHED");
        assertThat(activities(id,"SLA_BREACHED")).isEqualTo(2);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(published+1);
        validateEvents(id);
    }

    @Test void reasonChangeRollbackLeavesNoPartialBreachAuditNotificationOrEvent() {
        UUID id=create(false,type("LOW","CRITICAL","0"));
        var before=ticket(id); var audit=history(id); var schedules=cycles(id);
        int notifications=db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=?",Integer.class,id);
        db.execute("CREATE FUNCTION qa02_fail_reason_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.ticket_id='"+id+"'::uuid AND NEW.update_type='ESCALATION_CHANGED' THEN "
                + "RAISE EXCEPTION 'QA02 rollback'; END IF; RETURN NEW; END; $$");
        db.execute("CREATE TRIGGER qa02_fail_reason_change BEFORE INSERT ON outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION qa02_fail_reason_change()");
        try {
            assertThatThrownBy(() -> milestones.processCandidate(id,resolutionId(id),resolutionDue(id)))
                    .hasMessageContaining("QA02 rollback");
            assertThat(ticket(id)).isEqualTo(before);
            assertThat(history(id)).isEqualTo(audit);
            assertThat(cycles(id)).isEqualTo(schedules);
            assertThat(events(id,"ESCALATION_CHANGED")).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=?",Integer.class,id))
                    .isEqualTo(notifications);
        } finally {
            db.execute("DROP TRIGGER qa02_fail_reason_change ON outbox_events");
            db.execute("DROP FUNCTION qa02_fail_reason_change()");
        }
    }

    @Test void manualIsPreservedAndHistoricalBreachIsIgnored() {
        UUID id=create(false,type("LOW","LOW","0"));
        db.update("UPDATE tickets SET is_escalated=TRUE,escalation_reason_code='MANUAL',escalated_at=created_at WHERE id=?",id);
        var before=ticket(id);
        priorities.processCandidate(id,Instant.now()); assertThat(ticket(id)).isEqualTo(before);
        db.update("UPDATE tickets SET escalation_reason_code='SLA_BREACHED' WHERE id=?",id);
        db.update("UPDATE ticket_sla SET status='BREACHED',completed_at=due_at WHERE ticket_id=?",id);
        tx.executeWithoutResult(s -> milestones.reconcileEscalation(tickets.findByIdForUpdate(id).orElseThrow(),Instant.now(),true));
        assertThat(ticket(id)).containsEntry("is_escalated",false).containsEntry("escalation_reason_code",null).containsEntry("escalated_at",null);
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(1); validateEvents(id);
    }

    @Test void invalidCriticalFallsBackToActiveBreachWithoutRestartingActivation() {
        UUID id=create(false,type("LOW","CRITICAL","0")); Object activated=ticket(id).get("escalated_at");
        db.update("UPDATE tickets SET current_priority='LOW' WHERE id=?",id);
        db.update("UPDATE ticket_sla SET status='BREACHED' WHERE ticket_id=? AND sla_type='RESOLUTION'",id);
        tx.executeWithoutResult(s -> milestones.reconcileEscalation(tickets.findByIdForUpdate(id).orElseThrow(),Instant.now(),true));
        assertThat(ticket(id)).containsEntry("escalation_reason_code","SLA_BREACHED").containsEntry("escalated_at",activated);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1); validateEvents(id);
    }

    @Test void initialReclassificationRemovesCriticalAndDefersPublicationUntilRouting() {
        UUID id=create(false,type("LOW","CRITICAL","0"));
        creation.startReview(id,identity(agent,ModuleRole.AGENT));
        var proposal = reclassification.propose(id,
                new com.reclamos.backend.dto.request.ProposeReclassificationRequest(type("LOW","LOW","0"), null, null),
                identity(agent,ModuleRole.AGENT));
        reclassification.confirm(id, proposal.id(), identity(agent,ModuleRole.AGENT));
        assertThat(ticket(id)).containsEntry("current_priority","LOW").containsEntry("is_escalated",false)
                .containsEntry("escalation_reason_code",null).containsEntry("escalated_at",null);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1); assertThat(events(id,"ESCALATION_CHANGED")).isZero();
        assertThat(db.queryForMap("SELECT previous_priority,new_priority FROM ticket_activities WHERE ticket_id=? AND action_type='REQUEST_TYPE_CHANGED'",id))
                .containsEntry("previous_priority","CRITICAL").containsEntry("new_priority","LOW");
        validateEvents(id);
    }

    @Test void concurrencyWithBreachDoesNotDuplicateActivitiesOrRestartEscalation() throws Exception {
        UUID id=create(false,type("MEDIUM","LOW","1"));
        Instant due=resolutionDue(id); long slaId=resolutionId(id);
        race(() -> priorities.processCandidate(id,due), () -> milestones.processCandidate(id,slaId,due));
        assertThat(activities(id,"PRIORITY_CHANGED")).isEqualTo(1);
        assertThat(activities(id,"SLA_BREACHED")).isEqualTo(1);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1);
        int reasonChanges=db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? "
                + "AND action_type='STATE_CHANGED' AND metadata->>'previousReasonCode'='CRITICAL_PRIORITY' "
                + "AND metadata->>'reasonCode'='SLA_BREACHED'",Integer.class,id);
        assertThat(reasonChanges).isBetween(0,1);
        // Either the breach activates directly, or a real critical activation precedes the reason change.
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(1+reasonChanges);
        assertThat(ticket(id)).containsEntry("current_priority","CRITICAL")
                .containsEntry("escalation_reason_code","SLA_BREACHED"); validateEvents(id);
    }

    @Test void concurrencyBetweenRecalculatorsSerializesUpdates() throws Exception {
        UUID id=create(false,type("MEDIUM","LOW","1")); Instant due=resolutionDue(id);
        race(() -> priorities.processCandidate(id,due), () -> priorities.processCandidate(id,due));
        assertThat(activities(id,"PRIORITY_CHANGED")).isEqualTo(1);
        assertThat(activities(id,"ESCALATED")).isEqualTo(1); assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(1);
    }

    @Test void transitionLockIsRecheckedAfterCandidateSelection() throws Exception {
        UUID id=create(false,type("MEDIUM","LOW","1")); Instant due=resolutionDue(id);
        var held=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> transition=pool.submit(() -> tx.executeWithoutResult(s -> {
                Ticket t=tickets.findByIdForUpdate(id).orElseThrow(); held.countDown();
                await(release); t.setCurrentStatus(TicketStatus.CANCELLED);
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> recalc=pool.submit(() -> priorities.processCandidate(id,due));
            release.countDown(); transition.get(15,TimeUnit.SECONDS); recalc.get(15,TimeUnit.SECONDS);
        }
        assertThat(ticket(id)).containsEntry("current_status","CANCELLED").containsEntry("current_priority","HIGH");
        assertThat(activities(id,"PRIORITY_CHANGED")).isZero();
    }

    @Test void transactionRollbackLeavesNoPartialPriorityHistoryOrEvent() {
        UUID id=create(false,type("MEDIUM","LOW","1")); var before=ticket(id); var history=history(id);
        db.execute("CREATE FUNCTION h0708_fail_escalation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.ticket_id='"+id+"'::uuid AND NEW.update_type='ESCALATION_CHANGED' THEN "
                + "RAISE EXCEPTION 'H0708 rollback'; END IF; RETURN NEW; END; $$");
        db.execute("CREATE TRIGGER h0708_fail_escalation BEFORE INSERT ON outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION h0708_fail_escalation()");
        try {
            assertThatThrownBy(() -> priorities.processCandidate(id,resolutionDue(id))).hasMessageContaining("H0708 rollback");
            assertThat(ticket(id)).isEqualTo(before); assertThat(history(id)).isEqualTo(history);
            assertThat(events(id,"ESCALATION_CHANGED")).isZero();
        } finally {
            db.execute("DROP TRIGGER h0708_fail_escalation ON outbox_events");
            db.execute("DROP FUNCTION h0708_fail_escalation()");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void anonymousEscalationPublishesOnlyForConfirmedExternalManagement(boolean routed) {
        UUID id=create(true,type("MEDIUM","LOW","1"));
        if(routed) db.update("UPDATE tickets SET current_status='ROUTED',classification_finalized_at=created_at WHERE id=?",id);
        priorities.processCandidate(id,resolutionDue(id));
        assertThat(events(id,"ESCALATION_CHANGED")).isEqualTo(routed?1:0);
        validateEvents(id);
    }

    @Test void reopeningUsesFreshCycleAndDiscardsHistoricalBreachCause() {
        UUID id=create(false,type("LOW","LOW","0"));
        Instant due=resolutionDue(id);
        db.update("UPDATE ticket_sla SET status='BREACHED',completed_at=due_at WHERE ticket_id=?",id);
        db.update("UPDATE tickets SET is_escalated=TRUE,escalation_reason_code='SLA_BREACHED',escalated_at=created_at WHERE id=?",id);
        tx.executeWithoutResult(s -> {
            Ticket t=tickets.findByIdForUpdate(id).orElseThrow();
            t.setCurrentStatus(TicketStatus.IN_PROGRESS);
            lifecycle.startReopenedResolutionCycle(t,due.plusSeconds(1));
        });
        priorities.processCandidate(id,due.plusSeconds(1));
        assertThat(ticket(id)).containsEntry("current_priority","LOW").containsEntry("is_escalated",false);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION'",Integer.class,id))
                .isEqualTo(2);
        assertThat(activities(id,"PRIORITY_CHANGED")).isZero(); validateEvents(id);
    }

    @Test void repositoryUsesBoundedKeysetBatchesAndExcludesTerminalStates() {
        UUID a=create(false,type("LOW","LOW","0")), b=create(false,type("LOW","LOW","0"));
        UUID terminal=create(false,type("LOW","LOW","0"));
        db.update("UPDATE tickets SET current_status='CANCELLED' WHERE id=?",terminal);
        UUID upper=tickets.findPriorityScanUpperBound(org.springframework.data.domain.PageRequest.of(0,1)).getFirst();
        UUID after=new UUID(0,0); List<UUID> scanned=new ArrayList<>();
        while(true) {
            var batch=tickets.findPriorityCandidates(TicketPriorityService.ACTIVE_STATUSES,after,upper,
                    org.springframework.data.domain.PageRequest.of(0,1));
            assertThat(batch).hasSizeLessThanOrEqualTo(1);
            if(batch.isEmpty()) break; scanned.addAll(batch); after=batch.getLast();
        }
        assertThat(scanned).contains(a,b).doesNotContain(terminal).doesNotHaveDuplicates();
    }

    private void race(Runnable first,Runnable second) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> {await(start); first.run();}); var b=pool.submit(() -> {await(start); second.run();});
            start.countDown(); a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
        }
    }
    private void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private Long type(String risk,String minimum,String factor) {
        String code=marker+"-"+types.size();
        Long id=db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                +"SELECT ?,id,?,?,'REQUEST','M6',?,?,?,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id",Long.class,
                code,code,marker,minimum,risk,new BigDecimal(factor)); types.add(id); return id;
    }
    private UUID create(boolean anonymous,Long type) {
        var location=new CreateTicketRequest.LocationData("Lima 123",null,null,neighborhood,null,null,null);
        UUID id=creation.create(new CreateTicketRequest(type,marker,"H07 H08",Map.of(),location),
                anonymous?null:identity(citizen,ModuleRole.CITIZEN),null).ticketId(); ids.add(id); return id;
    }
    private AuthenticatedIdentity identity(UUID id,ModuleRole role) { return new AuthenticatedIdentity("h0708",id,"H07","M6",role); }
    private Map<String,Object> ticket(UUID id) { return db.queryForMap("SELECT * FROM tickets WHERE id=?",id); }
    private List<Map<String,Object>> cycles(UUID id) { return db.queryForList("SELECT * FROM ticket_sla WHERE ticket_id=? ORDER BY id",id); }
    private List<Map<String,Object>> history(UUID id) { return db.queryForList("SELECT * FROM ticket_activities WHERE ticket_id=? ORDER BY sequence",id); }
    private Instant resolutionDue(UUID id) { return db.queryForObject("SELECT due_at FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION' AND completed_at IS NULL",java.sql.Timestamp.class,id).toInstant(); }
    private long resolutionId(UUID id) { return db.queryForObject("SELECT id FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION' AND completed_at IS NULL",Long.class,id); }
    private int activities(UUID id,String action) { return db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? AND action_type=?",Integer.class,id,action); }
    private int events(UUID id,String update) { return db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=? AND update_type=?",Integer.class,id,update); }
    private void validateEvents(UUID id) {
        outbox.findAll().stream().filter(e -> e.getTicket().getId().equals(id)).forEach(e -> {
            PriorityEventContractAssertions.assertValid(e.getPayload()); PriorityEventContractAssertions.assertFullEventValid(e.getPayload());
        });
    }
}
