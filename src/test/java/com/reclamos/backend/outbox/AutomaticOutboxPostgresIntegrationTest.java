package com.reclamos.backend.outbox;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import com.reclamos.backend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"app.identity.provider=M1","app.simulator.enabled=false",
        "app.identity.m1.base-url=https://m1.invalid","app.identity.m1.browser-origin=https://m2.invalid",
        "app.identity.m1.jwt-secret-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "app.core.outbox.enabled=true","app.core.outbox.auto-approve-new-events=true","app.core.outbox.approved-event-ids=",
        "app.core.outbox.host=rabbit.invalid","app.core.outbox.port=5671","app.core.outbox.virtual-host=core",
        "app.core.outbox.username=synthetic","app.core.outbox.password=synthetic","app.core.outbox.max-attempts=3",
        "app.core.outbox.scan-delay=3600000","app.core.inbox.enabled=false","spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=3600000","ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000","notification.pending-scan-delay=3600000","ticket.priority.scan-delay=3600000"})
@ActiveProfiles("dev")
class AutomaticOutboxPostgresIntegrationTest {
    @Autowired CorePublicationPolicy policy;
    @Autowired CoreOutboxProcessor processor;
    @Autowired OutgoingEventValidator validator;
    @Autowired OutboxEventRepository events;
    @Autowired TicketRepository tickets;
    @Autowired ModuleUserRepository users;
    @Autowired RequestTypeRepository types;
    @Autowired TicketOutboxService outbox;
    @Autowired TicketSlaMilestoneService milestones;
    @Autowired TicketService ticketService;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate db;
    @MockitoBean(enforceOverride=true) CoreEventPublisher publisher;
    private final List<UUID> ticketIds=new ArrayList<>(),citizenIds=new ArrayList<>();
    private final List<Long> typeIds=new ArrayList<>();
    @BeforeEach void resetBoundary() { reset(publisher); }
    @AfterEach void cleanup() {
        for(UUID id:ticketIds) {
            for(String table:List.of("notification_logs","outbox_events","ticket_messages","ticket_activities","ticket_sla","ticket_locations"))
                db.update("DELETE FROM "+table+" WHERE ticket_id=?",id);
            db.update("DELETE FROM tickets WHERE id=?",id);
        }
        citizenIds.forEach(id -> db.update("DELETE FROM module_users WHERE actor_id=?",id));
        typeIds.forEach(id -> db.update("DELETE FROM request_types WHERE id=?",id));
    }
    @Test void actualCreationRecordsM1OriginForCitizensAndAnonymousTicketsButNeverMockCitizens() {
        Long type=type();
        for(boolean anonymous:List.of(false,true)) {
            ModuleUser user=anonymous?null:user(IdentitySource.M1);
            var request=new CreateTicketRequest(type,"Real ticket","Synthetic test",Map.of(),null);
            var response=ticketService.create(request,anonymous?null:identity(user),null); ticketIds.add(response.ticketId());
            var ticket=tickets.findById(response.ticketId()).orElseThrow();
            assertThat(ticket.isCoreOriginVerified()).isTrue();
            var rows=db.queryForList("SELECT auto_publish_eligible FROM outbox_events WHERE ticket_id=?",Boolean.class,ticket.getId());
            if(anonymous) assertThat(rows).isEmpty(); else assertThat(rows).containsExactly(true);
        }
        ModuleUser mock=user(IdentitySource.MOCK);
        var response=ticketService.create(new CreateTicketRequest(type,"Mock identity","Synthetic test",Map.of(),null),identity(mock),null);
        ticketIds.add(response.ticketId());
        assertThat(tickets.findById(response.ticketId()).orElseThrow().isCoreOriginVerified()).isFalse();
        assertThat(db.queryForList("SELECT auto_publish_eligible FROM outbox_events WHERE ticket_id=?",Boolean.class,response.ticketId())).containsExactly(false);
    }
    @Test void automaticWorkerPublishesNewEligibleSnapshotsAndNeverTouchesHistoricalOrMockEvents() {
        var historical=ticket(false,true); var mock=ticket(false,false,IdentitySource.MOCK);
        var real=ticket(false,true);
        OutboxEvent old=produce(historical,false),simulated=produce(mock,true),fresh=produce(real,true);
        assertThat(processor.processNextApproved()).isTrue(); assertThat(processor.processNextApproved()).isFalse();
        assertThat(events.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        for(var protectedRow:List.of(old,simulated)) {
            var stored=events.findById(protectedRow.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PENDING); assertThat(stored.getRetryCount()).isZero();
            assertThat(stored.isAutoPublishEligible()).isFalse(); assertThat(stored.getPayload()).isEqualTo(protectedRow.getPayload());
        }
        verify(publisher).publish(eq(fresh.getEventId()),eq("ticketCreated"),any());
    }
    @Test void earlierManualEventsRemainProtectedWhileNewEventsOnTheSameRealTicketBecomeEligible() {
        var ticket=ticket(false,true); var manual=produce(ticket,false); var automatic=produce(ticket,true);
        transactions.executeWithoutResult(tx -> {
            var existing=events.findById(manual.getId()).orElseThrow(); existing.setAutoPublishEligible(true); events.saveAndFlush(existing);
        });
        assertThat(events.findById(manual.getId()).orElseThrow().isAutoPublishEligible()).isFalse();
        processor.processNextApproved();
        assertThat(events.findById(manual.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(events.findById(automatic.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }
    @Test void manualModeStillSelectsOnlyExplicitUuidsEvenWhenOtherRowsAreAutomaticallyEligible() {
        var first=produce(ticket(false,true),true); var selected=produce(ticket(false,true),true);
        var manual=new CoreOutboxProcessor(events,validator,publisher,OutboxFixtures.settings(Set.of(selected.getEventId())),
                Clock.systemUTC(),new CorePublicationPolicy(false,true,"M1",false,selected.getEventId().toString(),users));
        Boolean processed=transactions.execute(tx -> manual.processNextApproved());
        Boolean repeated=transactions.execute(tx -> manual.processNextApproved());
        assertThat(processed).isTrue(); assertThat(repeated).isFalse();
        assertThat(events.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(events.findById(selected.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(publisher).publish(eq(selected.getEventId()),any(),any());
    }
    @Test void anonymousEscalationsWithoutAnyActorUsePersistedOriginButHistoricalTicketsStayProtected() {
        for(boolean verified:List.of(false,true)) {
            var ticket=ticket(true,verified);
            transactions.executeWithoutResult(tx -> {
                var managed=tickets.findById(ticket.getId()).orElseThrow(); managed.setCurrentPriority(Priority.CRITICAL);
                managed.setCurrentStatus(TicketStatus.IN_PROGRESS); managed.setClassificationFinalizedAt(Instant.now());
                milestones.reconcileEscalation(managed,Instant.now(),true);
            });
            assertThat(db.queryForObject("SELECT auto_publish_eligible FROM outbox_events WHERE ticket_id=?",Boolean.class,ticket.getId())).isEqualTo(verified);
        }
        assertThat(processor.processNextApproved()).isTrue(); assertThat(processor.processNextApproved()).isFalse();
        verify(publisher).publish(any(),eq("ticketUpdated"),any());
    }
    @Test void regularAnonymousUpdatesAreAdmittedByTheSamePolicy() {
        var ticket=ticket(true,true);
        transactions.executeWithoutResult(tx -> {
            var managed=tickets.findById(ticket.getId()).orElseThrow(); managed.setCurrentStatus(TicketStatus.CLOSED);
            outbox.closed(managed,"ADMINISTRATIVE_CLOSE",Instant.now());
        });
        assertThat(db.queryForObject("SELECT auto_publish_eligible FROM outbox_events WHERE ticket_id=?",Boolean.class,ticket.getId())).isTrue();
        assertThat(processor.processNextApproved()).isTrue(); verify(publisher).publish(any(),eq("ticketUpdated"),any());
    }
    @Test void failedCreationRollsBackEligibilityWithTheEventAndBusinessTransaction() {
        var ticket=ticket(false,true);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            var managed=tickets.findById(ticket.getId()).orElseThrow(); managed.setSummary("rolled back");
            outbox.statusChanged(managed,null,Instant.now()); events.flush(); throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=?",Integer.class,ticket.getId())).isZero();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getSummary()).isEqualTo("Synthetic ticket");
    }
    @Test void retryAndReplacementWorkerKeepTheSameEligibleEventIdAndPayload() {
        var event=produce(ticket(false,true),true);
        doThrow(new PublicationFailure(PublicationFailure.Code.CONFIRM_TIMEOUT)).doNothing().when(publisher).publish(any(),any(),any());
        processor.processNextApproved();
        var pending=events.findById(event.getId()).orElseThrow(); assertThat(pending.isAutoPublishEligible()).isTrue();
        assertThat(pending.getNextAttemptAt()).isAfter(Instant.now()); assertThat(processor.processNextApproved()).isFalse();
        db.update("UPDATE outbox_events SET next_attempt_at=now()-interval '1 second' WHERE id=?",event.getId());
        new CoreOutboxWorker(processor).publishNext();
        var published=events.findById(event.getId()).orElseThrow(); assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getPayload()).isEqualTo(event.getPayload()); assertThat(published.getEventId()).isEqualTo(event.getEventId());
        assertThat(published.getRetryCount()).isEqualTo(2); verify(publisher,times(2)).publish(eq(event.getEventId()),any(),any());
    }
    @Test void concurrentWorkersSkipTheOccupiedEligibleRow() throws Exception {
        var event=produce(ticket(false,true),true); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); return null; })
                .when(publisher).publish(any(),any(),any());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(processor::processNextApproved);
            try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(executor.submit(processor::processNextApproved).get(3,TimeUnit.SECONDS)).isFalse(); }
            finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        verify(publisher,times(1)).publish(eq(event.getEventId()),any(),any());
    }
    @Test void uncertainCommitCanRedeliverOnlyTheSameImmutableEligibleEnvelope() {
        var event=produce(ticket(false,true),true);
        doAnswer(i -> { TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("synthetic commit failure"); }
        }); return null; }).when(publisher).publish(any(),any(),any());
        assertThatThrownBy(processor::processNextApproved).isInstanceOf(IllegalStateException.class);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
        doNothing().when(publisher).publish(any(),any(),any()); processor.processNextApproved();
        var body=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(publisher,times(2)).publish(eq(event.getEventId()),any(),body.capture());
        assertThat(body.getAllValues().get(0)).isEqualTo(body.getAllValues().get(1));
    }
    private Ticket ticket(boolean anonymous,boolean verified) { return ticket(anonymous,verified,IdentitySource.M1); }
    private Ticket ticket(boolean anonymous,boolean verified,IdentitySource source) {
        ModuleUser user=anonymous?null:user(source);
        return transactions.execute(tx -> {
            var t=new Ticket(); t.setAnonymous(anonymous); t.setCitizenId(anonymous?null:user.getCitizenId());
            t.setCoreOriginVerified(verified&&policy.realOriginForNewTicket(t));
            t.setPublicId("TK-2026-%06d".formatted(db.queryForObject("SELECT nextval('ticket_public_id_seq')",Long.class)));
            t.setTrackingCodeHash("auto-test-"+UUID.randomUUID()); t.setAnonymousAccessPasswordHash(anonymous?"synthetic-hash":null);
            var type=types.findAll().stream().filter(RequestType::isActive).findFirst().orElseThrow();
            t.setRequestType(type); t.setTicketType(type.getTicketType()); t.setResponsibleAreaId("M6"); t.setSummary("Synthetic ticket");
            t.setDescription("Disposable test"); t.setFormData(Map.of()); t.setCurrentStatus(TicketStatus.REGISTERED);
            t.setCurrentPriority(Priority.MEDIUM); t.setStatusChangedAt(Instant.now()); tickets.saveAndFlush(t); ticketIds.add(t.getId()); return t;
        });
    }
    private OutboxEvent produce(Ticket ticket,boolean automatic) {
        Long eventId=transactions.execute(tx -> {
            var producer=automatic?outbox:new TicketOutboxService(events,Clock.systemUTC(),mock(AttachmentReferenceService.class),
                    mock(AttachmentRepository.class),mock(FormValidationService.class),new CorePublicationPolicy(false,true,"M1",false,"",users));
            producer.ticketCreated(tickets.findById(ticket.getId()).orElseThrow(),null,List.of(),null); events.flush();
            return events.findAll().stream().filter(e -> e.getTicket().getId().equals(ticket.getId())).max(Comparator.comparing(OutboxEvent::getId)).orElseThrow().getId();
        });
        return events.findById(eventId).orElseThrow();
    }
    private ModuleUser user(IdentitySource source) {
        var u=new ModuleUser(); UUID id=UUID.randomUUID(); citizenIds.add(id); u.setActorId(id);u.setCitizenId(id);
        u.setActorType(IdentityActorType.CIUDADANO);u.setIdentitySource(source);u.setRole(ModuleRole.CITIZEN);
        u.setFirstName("Test");u.setLastName("Citizen");u.setLastSyncedAt(Instant.now());return users.saveAndFlush(u);
    }
    private AuthenticatedIdentity identity(ModuleUser u) { return new AuthenticatedIdentity("test",u.getActorId(),u.getActorType(),u.getCitizenId(),"Test Citizen",null,ModuleRole.CITIZEN); }
    private Long type() {
        String code="AUTO_"+UUID.randomUUID();
        Long id=db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                +"SELECT ?,id,?,?,'REQUEST','M6','LOW','LOW',0,true,false,true FROM subcategories ORDER BY id LIMIT 1 RETURNING id",Long.class,code,code,code);
        typeIds.add(id);return id;
    }
}
