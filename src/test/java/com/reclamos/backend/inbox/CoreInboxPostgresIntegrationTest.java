package com.reclamos.backend.inbox;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import com.reclamos.backend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.reclamos.backend.inbox.IncomingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Commits on disposable PostgreSQL, with no broker or external identity services. */
@SpringBootTest(properties={"app.core.inbox.enabled=false","app.core.outbox.enabled=false",
        "spring.jpa.show-sql=false","ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000","ticket.sla.milestone-scan-delay=3600000",
        "notification.pending-scan-delay=3600000","ticket.priority.scan-delay=3600000"})
@ActiveProfiles("dev")
class CoreInboxPostgresIntegrationTest {
    @Autowired CoreInboxProcessor processor;
    @Autowired CoreIncomingEventValidator validator;
    @Autowired CitizenEventProjection projection;
    @Autowired TicketStatusUpdateService updates;
    @Autowired TicketSlaService sla;
    @Autowired ModuleUserService synchronization;
    @Autowired TicketRepository tickets;
    @Autowired RequestTypeRepository types;
    @Autowired ModuleUserRepository users;
    @Autowired InboxEventRepository inbox;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate db;
    @Autowired Clock clock;
    private final List<UUID> ticketIds=new ArrayList<>(), citizenIds=new ArrayList<>(), eventIds=new ArrayList<>();

    @AfterEach void cleanup() {
        eventIds.forEach(id -> db.update("DELETE FROM inbox_events WHERE event_id=?",id));
        for (UUID id:ticketIds) {
            db.update("DELETE FROM information_request_attachments WHERE information_request_id IN (SELECT id FROM information_requests WHERE ticket_id=?)",id);
            for (String table:List.of("notification_logs","outbox_events","ticket_messages","ticket_activities",
                    "ticket_resolutions","information_requests","ticket_cancellations","ticket_sla","ticket_locations")) {
                db.update("DELETE FROM "+table+" WHERE ticket_id=?",id);
            }
            db.update("UPDATE tickets SET main_ticket_id=NULL WHERE id=?",id);
        }
        ticketIds.forEach(id -> db.update("DELETE FROM tickets WHERE id=?",id));
        citizenIds.forEach(id -> db.update("DELETE FROM module_users WHERE actor_id=?",id));
    }

    @ParameterizedTest @ValueSource(strings={"STARTED","PROGRESS","INFORMATION_REQUIRED","RETURNED","RESOLVED","REJECTED"})
    void officialFactsReuseCanonicalRulesCommitMessagesAndNeverEcho(String type) {
        Ticket ticket=ticket(type.equals("PROGRESS")?TicketStatus.IN_PROGRESS:TicketStatus.ROUTED);
        var event=track(status(ticket.getId(),type)); UUID correlation=UUID.randomUUID(),cause=UUID.randomUUID();
        event.put("correlationId",correlation.toString()).put("causationId",cause.toString());
        processor.process(bytes(event)); processor.process(bytes(event));
        TicketStatus expected=switch(type) {
            case "STARTED","PROGRESS" -> TicketStatus.IN_PROGRESS;
            case "INFORMATION_REQUIRED" -> TicketStatus.PENDING_INFORMATION;
            case "RETURNED" -> TicketStatus.IN_REVIEW;
            case "RESOLVED" -> TicketStatus.RESOLVED;
            default -> TicketStatus.CANCELLED;
        };
        var stored=tickets.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getCurrentStatus()).isEqualTo(expected);
        assertThat(stored.getExternalUpdateAt()).isEqualTo(Instant.parse(TIME));
        assertThat(stored.getCitizenId()).isEqualTo(ticket.getCitizenId());
        assertThat(count("ticket_activities",ticket)).isEqualTo(1);
        assertThat(count("outbox_events",ticket)).isZero();
        var receipt=inbox.findById(id(event)).orElseThrow();
        assertThat(receipt.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(receipt.getPayload()).containsEntry("disposition","APPLIED")
                .containsEntry("correlationId",correlation.toString()).containsEntry("causationId",cause.toString());
        assertThat(receipt.getPayload().toString()).doesNotContain("Corresponde reclasificar","Trabajo terminado","updatedBy");
        assertThat(receipt.getPayloadHash()).hasSize(64);
    }
    @Test void prematureProgressRollsBackWithoutInboxAndSameEventCanSucceedAfterStarted() {
        Ticket ticket=ticket(TicketStatus.ROUTED);
        var progress=track(status(ticket.getId(),"PROGRESS")); data(progress).put("updateOccurredAt","2026-10-01T12:01:00Z");
        assertThatThrownBy(() -> processor.process(bytes(progress))).hasMessage("PREMATURE_PROGRESS");
        assertThat(inbox.existsById(id(progress))).isFalse(); assertThat(count("ticket_activities",ticket)).isZero();
        processor.process(bytes(track(status(ticket.getId(),"STARTED")))); processor.process(bytes(progress));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentProgress()).isEqualTo((short)30);
        assertThat(inbox.existsById(id(progress))).isTrue();
    }
    @Test void ignoresWrongAreaUnknownTicketInvalidStateAndOutOfOrderUpdatesWithAnAuditReceipt() {
        Ticket ticket=ticket(TicketStatus.ROUTED);
        var wrong=track(status(ticket.getId(),"STARTED")); wrong.put("sourceModule","obras");
        processAndAssert(wrong,"IGNORED_OTHER_AREA"); assertThat(count("ticket_activities",ticket)).isZero();
        processAndAssert(track(status(UUID.randomUUID(),"STARTED")),"IGNORED_UNKNOWN_TICKET");
        processor.process(bytes(track(status(ticket.getId(),"STARTED"))));
        processAndAssert(track(status(ticket.getId(),"STARTED")),"IGNORED_STATE_CONFLICT");
        var old=track(status(ticket.getId(),"PROGRESS")); data(old).put("updateOccurredAt","2026-09-30T12:00:00Z");
        processAndAssert(old,"IGNORED_OLD_UPDATE"); assertThat(count("ticket_activities",ticket)).isEqualTo(1);
    }
    @Test void unrelatedEventsAreIgnoredAndUnsupportedProducersNeverApplyTicketEffects() {
        var other=track(envelope("unrelatedEvent","ciudadanos",JSON.createObjectNode()));
        processor.process(bytes(other)); assertThat(inbox.existsById(id(other))).isFalse();
        Ticket ticket=ticket(TicketStatus.ROUTED);
        var wrong=track(status(ticket.getId(),"STARTED")); wrong.put("sourceModule","ciudadanos");
        processAndAssert(wrong,"IGNORED_PRODUCER"); assertThat(count("ticket_activities",ticket)).isZero();
    }
    @Test void attachmentsAreNeverSilentlyDroppedAndLeaveNoPermanentInboxRecord() {
        Ticket ticket=ticket(TicketStatus.ROUTED);
        var event=track(status(ticket.getId(),"STARTED"));
        data(event).putArray("attachments").addObject().put("fileName","evidence.pdf").put("contentType","application/pdf")
                .put("url","https://external.invalid/api/files/1").put("sizeBytes",42);
        assertThatThrownBy(() -> processor.process(bytes(event))).hasMessage("EXTERNAL_ATTACHMENTS_UNSUPPORTED");
        assertThat(inbox.existsById(id(event))).isFalse(); assertThat(count("ticket_activities",ticket)).isZero();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
    }
    @Test void sameEventIdWithDifferentContentIsRejectedWithoutChangingTheCommittedFact() {
        UUID citizen=citizenId(); var event=track(citizen(citizen,"REGISTERED")); details(event).put("name","Original");
        processor.process(bytes(event)); details(event).put("name","Changed");
        assertThatThrownBy(() -> processor.process(bytes(event))).hasMessage("EVENT_ID_COLLISION");
        assertThat(users.findByActorId(citizen).orElseThrow().getFirstName()).isEqualTo("Original");
    }
    @Test void simultaneousDuplicateDeliveriesCommitExactlyOneSetOfEffects() throws Exception {
        Ticket ticket=ticket(TicketStatus.ROUTED); var event=track(status(ticket.getId(),"STARTED"));
        byte[] body=bytes(event); CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Void> work=() -> { barrier.await(5,TimeUnit.SECONDS); processor.process(body); return null; };
            var one=executor.submit(work); var two=executor.submit(work);
            one.get(10,TimeUnit.SECONDS); two.get(10,TimeUnit.SECONDS);
        }
        assertThat(count("ticket_activities",ticket)).isEqualTo(1); assertThat(inbox.existsById(id(event))).isTrue();
    }
    @Test void independentSlaFactKeepsIncomingCorrelationAndUsesIncomingEventAsCausation() {
        Ticket ticket=ticket(TicketStatus.ROUTED);
        transactions.executeWithoutResult(tx -> {
            var managed=tickets.findById(ticket.getId()).orElseThrow();
            sla.startInitialResolutionCycle(managed,managed.getCreatedAt());
        });
        db.update("UPDATE ticket_sla SET status='BREACHED',started_at=now()-interval '300 seconds',near_due_at=now()-interval '200 seconds',due_at=now()-interval '100 seconds' WHERE ticket_id=?",ticket.getId());
        db.update("UPDATE tickets SET is_escalated=true,escalation_reason_code='SLA_BREACHED',escalated_at=now()-interval '100 seconds' WHERE id=?",ticket.getId());
        var event=track(status(ticket.getId(),"RESOLVED")); UUID correlation=UUID.randomUUID();
        event.put("correlationId",correlation.toString()).put("causationId",UUID.randomUUID().toString());
        data(event).put("updateOccurredAt",Instant.now().toString());
        processor.process(bytes(event));
        assertThat(db.queryForObject("SELECT update_type FROM outbox_events WHERE ticket_id=?",String.class,ticket.getId())).isEqualTo("ESCALATION_CHANGED");
        assertThat(db.queryForMap("SELECT payload->>'correlationId' AS correlation,payload->>'causationId' AS causation FROM outbox_events WHERE ticket_id=?",ticket.getId()))
                .containsEntry("correlation",correlation.toString()).containsEntry("causation",id(event).toString());
    }
    @Test void rejectedMainWithDuplicatesCommitsExistingScopeReviewAuditAndAnIgnoredReceipt() {
        Ticket main=ticket(TicketStatus.ROUTED),child=ticket(TicketStatus.DUPLICATE);
        db.update("UPDATE tickets SET main_ticket_id=? WHERE id=?",main.getId(),child.getId());
        var event=track(status(main.getId(),"REJECTED"));
        processAndAssert(event,"IGNORED_CANCELLATION_SCOPE_REVIEW"); processor.process(bytes(event));
        assertThat(tickets.findById(main.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(tickets.findById(child.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.DUPLICATE);
        assertThat(count("ticket_activities",main)).isEqualTo(1); assertThat(count("ticket_cancellations",main)).isZero();
    }
    @Test void failureAfterBusinessEffectsRollsBackEverythingAndRedeliveryRecovers() {
        Ticket ticket=ticket(TicketStatus.ROUTED); var event=track(status(ticket.getId(),"STARTED"));
        InboxEventRepository failing=mock(InboxEventRepository.class,delegatesTo(inbox));
        doThrow(new DataAccessResourceFailureException("simulated write failure")).when(failing).saveAndFlush(any());
        var failed=new CoreInboxProcessor(validator,failing,tickets,updates,projection,clock);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> failed.process(bytes(event))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(inbox.existsById(id(event))).isFalse(); assertThat(count("ticket_activities",ticket)).isZero();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        processor.process(bytes(event)); assertThat(count("ticket_activities",ticket)).isEqualTo(1);
    }
    @Test void commitFailureRollsBackProjectionAndInboxSoRetryIsSafe() {
        UUID citizen=citizenId(); var event=track(citizen(citizen,"BLOCKED"));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            processor.process(bytes(event));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("simulated commit failure"); }
            });
        })).isInstanceOf(IllegalStateException.class);
        assertThat(inbox.existsById(id(event))).isFalse(); assertThat(users.findByActorId(citizen)).isEmpty();
        processor.process(bytes(event)); assertThat(users.findByActorId(citizen).orElseThrow().getExternalStatus()).isEqualTo(ExternalCitizenStatus.BLOCKED);
    }
    @Test void brokerAckObservesCommittedDatabaseEffectsAndFailureNacksWithoutInbox() throws Exception {
        UUID citizen=citizenId(); var event=track(citizen(citizen,"REGISTERED"));
        Channel channel=mock(Channel.class); Connection connection=mock(Connection.class);
        var consumer=new CoreEventConsumer(mock(ConnectionFactory.class),processor,1);
        doAnswer(i -> {
            assertThat(inbox.existsById(id(event))).isTrue();
            assertThat(users.findByActorId(citizen)).isPresent(); return null;
        }).when(channel).basicAck(7,false);
        consumer.receive(channel,connection,new Delivery(new Envelope(7,false,"",""),new AMQP.BasicProperties(),bytes(event)));
        verify(channel).basicAck(7,false);
        Ticket ticket=ticket(TicketStatus.ROUTED); var premature=track(status(ticket.getId(),"PROGRESS"));
        consumer.receive(channel,connection,new Delivery(new Envelope(8,false,"",""),new AMQP.BasicProperties(),bytes(premature)));
        verify(channel).basicNack(8,false,false); assertThat(inbox.existsById(id(premature))).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"REGISTERED","BLOCKED","DECEASED","ADDRESS_UPDATED"})
    void citizenVariantsCanArriveBeforeFirstLoginWithoutFabricatedNamesOrStoredPii(String type) {
        UUID citizen=citizenId(); var event=track(citizen(citizen,type));
        details(event).put("dni","synthetic-dni").put("cuil","synthetic-cuil");
        if(type.equals("ADDRESS_UPDATED")) details(event).putObject("newAddress").put("street","Private street").put("propertyId",5602);
        processor.process(bytes(event)); processor.process(bytes(event));
        var user=users.findByActorId(citizen).orElseThrow();
        assertThat(user.getCitizenId()).isEqualTo(citizen); assertThat(user.getActorId()).isEqualTo(citizen);
        assertThat(user.getActorType()).isEqualTo(IdentityActorType.CIUDADANO); assertThat(user.getIdentitySource()).isEqualTo(IdentitySource.M1);
        assertThat(user.getFirstName()).isNull(); assertThat(user.getLastName()).isNull();
        assertThat(user.getRole()).isEqualTo(ModuleRole.CITIZEN); assertThat(user.getAreaId()).isNull(); assertThat(user.isActive()).isTrue();
        assertThat(inbox.findById(id(event)).orElseThrow().getPayload().toString()).doesNotContain("synthetic-dni","synthetic-cuil","Private street","details");
    }
    @Test void outOfOrderIndependentStreamsAndStickyRestrictionsPreserveLocalAuthorizationOwnershipAndLocations() {
        UUID citizen=citizenId(); var registered=track(citizen(citizen,"REGISTERED"));
        details(registered).put("name","Ana").put("lastname","Nueva"); processor.process(bytes(registered));
        var user=users.findByActorId(citizen).orElseThrow();
        user.setRole(ModuleRole.AREA_RESPONSIBLE); user.setAreaId("M6"); user.setActive(false); user.setPreferredNotificationChannel("EMAIL"); users.saveAndFlush(user);
        Ticket ticket=ticket(TicketStatus.ROUTED);
        db.update("UPDATE tickets SET citizen_id=? WHERE id=?",citizen,ticket.getId());
        db.update("INSERT INTO ticket_locations(ticket_id,address_line,created_at,updated_at) VALUES (?,'Historical ticket address',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",ticket.getId());
        var address=track(citizen(citizen,"ADDRESS_UPDATED")); address.put("occurredAt","2026-10-03T12:00:00Z");
        details(address).putObject("newAddress").put("street","New street"); processor.process(bytes(address));
        var blocked=track(citizen(citizen,"BLOCKED")); blocked.put("occurredAt","2026-09-01T12:00:00Z"); processor.process(bytes(blocked));
        var oldProfile=track(citizen(citizen,"REGISTERED")); oldProfile.put("occurredAt","2026-09-02T12:00:00Z"); details(oldProfile).put("name","Old"); processor.process(bytes(oldProfile));
        user=users.findByActorId(citizen).orElseThrow();
        assertThat(user.getFirstName()).isEqualTo("Ana"); assertThat(user.getLastName()).isEqualTo("Nueva");
        assertThat(user.getExternalStatus()).isEqualTo(ExternalCitizenStatus.BLOCKED);
        assertThat(user.getExternalAddressUpdatedAt()).isEqualTo(Instant.parse("2026-10-03T12:00:00Z"));
        assertThat(user.getExternalProfileUpdatedAt()).isEqualTo(Instant.parse(TIME));
        assertThat(user.getRole()).isEqualTo(ModuleRole.AREA_RESPONSIBLE); assertThat(user.getAreaId()).isEqualTo("M6");
        assertThat(user.isActive()).isFalse(); assertThat(user.getPreferredNotificationChannel()).isEqualTo("EMAIL");
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCitizenId()).isEqualTo(citizen);
        assertThat(db.queryForObject("SELECT address_line FROM ticket_locations WHERE ticket_id=?",String.class,ticket.getId())).isEqualTo("Historical ticket address");
        assertThat(count("outbox_events",ticket)).isZero();
        var died=track(citizen(citizen,"DECEASED")); processor.process(bytes(died));
        var later=track(citizen(citizen,"REGISTERED")); later.put("occurredAt","2026-10-05T12:00:00Z"); processor.process(bytes(later));
        processor.process(bytes(track(citizen(citizen,"BLOCKED")))) ;
        assertThat(users.findByActorId(citizen).orElseThrow().getExternalStatus()).isEqualTo(ExternalCitizenStatus.DECEASED);
    }
    @Test void partialNamesMergeAtSameTimestampAndLoginRemainsAComplementaryProfileSource() {
        UUID citizen=citizenId(); var first=track(citizen(citizen,"REGISTERED")); details(first).put("name","Partial"); processor.process(bytes(first));
        var second=track(citizen(citizen,"REGISTERED")); details(second).put("lastname","Citizen"); processor.process(bytes(second));
        var user=users.findByActorId(citizen).orElseThrow(); assertThat(user.getFirstName()).isEqualTo("Partial"); assertThat(user.getLastName()).isEqualTo("Citizen");
        var fromLogin=synchronization.synchronize(profile(citizen,"Login", "Current"));
        assertThat(fromLogin.getId()).isEqualTo(user.getId()); assertThat(fromLogin.getFirstName()).isEqualTo("Login");
        var old=track(citizen(citizen,"REGISTERED")); details(old).put("name","Old"); processor.process(bytes(old));
        assertThat(users.findByActorId(citizen).orElseThrow().getFirstName()).isEqualTo("Login");
    }
    @Test void concurrentFirstLoginAndEventCreateOnlyOneCanonicalM1Identity() throws Exception {
        UUID citizen=citizenId(); var blocked=track(citizen(citizen,"BLOCKED")); CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var login=executor.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return synchronization.synchronize(profile(citizen,"Login","User")); });
            var event=executor.submit(() -> { barrier.await(5,TimeUnit.SECONDS); processor.process(bytes(blocked)); return true; });
            login.get(10,TimeUnit.SECONDS); event.get(10,TimeUnit.SECONDS);
        }
        var user=users.findByActorId(citizen).orElseThrow(); assertThat(user.getFirstName()).isEqualTo("Login");
        assertThat(user.getExternalStatus()).isEqualTo(ExternalCitizenStatus.BLOCKED);
        assertThat(db.queryForObject("SELECT count(*) FROM module_users WHERE actor_id=?",Integer.class,citizen)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void citizenProjectionNeverLinksToMockUsersOrEmployees(boolean employee) {
        UUID id=citizenId(); ModuleUser user=new ModuleUser(); user.setActorId(id); user.setCitizenId(employee?null:id);
        user.setActorType(employee?IdentityActorType.EMPLEADO:IdentityActorType.CIUDADANO);
        user.setIdentitySource(employee?IdentitySource.M1:IdentitySource.MOCK); user.setFirstName("Existing"); user.setLastName("Staff");
        user.setRole(ModuleRole.ADMIN); user.setLastSyncedAt(clock.instant()); users.saveAndFlush(user);
        var event=track(citizen(id,"BLOCKED"));
        assertThatThrownBy(() -> processor.process(bytes(event))).hasMessage("WRONG_PRODUCER");
        assertThat(inbox.existsById(id(event))).isFalse();
        var unchanged=users.findByActorId(id).orElseThrow(); assertThat(unchanged.getExternalStatus()).isEqualTo(ExternalCitizenStatus.UNKNOWN);
        assertThat(unchanged.getRole()).isEqualTo(ModuleRole.ADMIN); assertThat(unchanged.getCitizenId()).isEqualTo(employee?null:id);
    }
    private Ticket ticket(TicketStatus state) {
        UUID citizen=citizenId(); ModuleUser user=new ModuleUser(); user.setActorId(citizen); user.setCitizenId(citizen);
        user.setIdentitySource(IdentitySource.M1); user.setActorType(IdentityActorType.CIUDADANO);
        user.setFirstName("Test"); user.setLastName("Citizen"); user.setRole(ModuleRole.CITIZEN); user.setLastSyncedAt(clock.instant()); users.saveAndFlush(user);
        Ticket ticket=transactions.execute(tx -> {
            var request=types.findAll().stream().filter(RequestType::isActive).findFirst().orElseThrow();
            Ticket t=new Ticket(); t.setPublicId("M2-"+db.queryForObject("SELECT nextval('ticket_public_id_seq')",Long.class));
            t.setTrackingCodeHash("inbox-"+UUID.randomUUID()); t.setCitizenId(citizen); t.setRequestType(request); t.setTicketType(request.getTicketType());
            t.setResponsibleAreaId("M6"); t.setSummary("Inbox integration"); t.setDescription("Synthetic fixture"); t.setFormData(Map.of());
            t.setCurrentStatus(state); t.setStatusChangedAt(Instant.parse(TIME).minusSeconds(60)); t.setCurrentPriority(Priority.MEDIUM);
            return tickets.saveAndFlush(t);
        });
        ticketIds.add(ticket.getId()); return ticket;
    }
    private UUID citizenId() { UUID id=UUID.randomUUID(); citizenIds.add(id); return id; }
    private ObjectNode track(ObjectNode event) { eventIds.add(id(event)); return event; }
    private UUID id(ObjectNode event) { return UUID.fromString(event.get("eventId").asText()); }
    private int count(String table,Ticket ticket) { return db.queryForObject("SELECT count(*) FROM "+table+" WHERE ticket_id=?",Integer.class,ticket.getId()); }
    private void processAndAssert(ObjectNode event,String disposition) {
        processor.process(bytes(event)); assertThat(inbox.findById(id(event)).orElseThrow().getPayload()).containsEntry("disposition",disposition);
    }
    private ExternalIdentityProfile profile(UUID citizen,String first,String last) {
        return new ExternalIdentityProfile("14",citizen,IdentityActorType.CIUDADANO,IdentitySource.M1,citizen,first,last,null,null,null,null);
    }
}
