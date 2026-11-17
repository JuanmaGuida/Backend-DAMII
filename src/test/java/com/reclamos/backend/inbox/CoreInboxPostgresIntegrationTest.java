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
import org.junit.jupiter.params.provider.CsvSource;
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
    @Autowired InformationRequestService information;
    @Autowired TicketResolutionService resolutions;
    @Autowired InformationRequestExpirationService expiration;
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
    @ParameterizedTest @ValueSource(strings={"M6","M3","M4","M7","M5","M8"})
    void prematureProgressRollsBackWithoutInboxAndSameEventCanSucceedAfterStarted(String area) {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        var progress=externalEvent(area,ticket,"PROGRESS"); data(progress).put("updateOccurredAt","2026-10-01T12:01:00Z");
        assertThatThrownBy(() -> processor.process(bytes(progress))).hasMessage("PREMATURE_PROGRESS");
        assertThat(inbox.existsById(id(progress))).isFalse(); assertThat(count("ticket_activities",ticket)).isZero();
        processor.process(bytes(externalEvent(area,ticket,"STARTED"))); processor.process(bytes(progress));
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
    // Synthetic M2 compatibility scenarios: M5 has no bilateral contract or homologation.
    @ParameterizedTest @CsvSource({"M3,STARTED","M3,PROGRESS","M3,INFORMATION_REQUIRED","M3,RETURNED","M3,RESOLVED","M3,REJECTED","M4,STARTED","M4,PROGRESS","M4,INFORMATION_REQUIRED","M4,RETURNED","M4,RESOLVED","M4,REJECTED","M7,STARTED","M7,PROGRESS","M7,INFORMATION_REQUIRED","M7,RETURNED","M7,RESOLVED","M7,REJECTED","M5,STARTED","M5,PROGRESS","M5,INFORMATION_REQUIRED","M5,RETURNED","M5,RESOLVED","M5,REJECTED","M6,STARTED","M6,PROGRESS","M6,INFORMATION_REQUIRED","M6,RETURNED","M6,RESOLVED","M6,REJECTED","M8,STARTED","M8,PROGRESS","M8,INFORMATION_REQUIRED","M8,RETURNED","M8,RESOLVED","M8,REJECTED"})
    void externalFactsWithEvidenceCommitAtomicallyWithoutEchoOrDuplicateReferences(String area,String type) {
        Ticket ticket=externalTicket(area,type.equals("PROGRESS")?TicketStatus.IN_PROGRESS:TicketStatus.ROUTED);
        var event=externalEvent(area,ticket,type);
        if(type.equals("PROGRESS")) data(event).remove("progress"); // evidence alone is meaningful
        evidence(event);
        processor.process(bytes(event)); processor.process(bytes(event));
        TicketStatus expected=switch(type) {
            case "STARTED","PROGRESS" -> TicketStatus.IN_PROGRESS;
            case "INFORMATION_REQUIRED" -> TicketStatus.PENDING_INFORMATION;
            case "RETURNED" -> TicketStatus.IN_REVIEW;
            case "RESOLVED" -> TicketStatus.RESOLVED;
            default -> TicketStatus.CANCELLED;
        };
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(expected);
        var ref=db.queryForMap("SELECT * FROM external_attachment_references WHERE ticket_id=?",ticket.getId());
        assertThat(ref).containsEntry("external_event_id",id(event)).containsEntry("source_module_id",area)
                .containsEntry("update_type",type).containsEntry("visibility","INTERNAL").containsEntry("size_bytes",null);
        assertThat(count("attachments",ticket)).isZero(); // never masquerades as a local file
        assertThat(count("ticket_activities",ticket)).isEqualTo(1);
        assertThat(count("outbox_events",ticket)).isZero();
        if(type.equals("RESOLVED")) assertThat(count("ticket_resolutions",ticket)).isEqualTo(1);
        assertThat(inbox.findById(id(event)).orElseThrow().getPayload().toString()).doesNotContain("external.invalid");
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7","M5","M6","M8"})
    void externalCanResolveDirectlyFromInProgressWithPhotoAndPreservePublicMessage(String area) {
        Ticket ticket=externalTicket(area,TicketStatus.IN_PROGRESS); var event=externalEvent(area,ticket,"RESOLVED"); evidence(event);
        processor.process(bytes(event));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(count("external_attachment_references",ticket)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT text FROM ticket_messages WHERE ticket_id=? AND visibility='PUBLIC'",String.class,ticket.getId()))
                .isEqualTo("Trabajo terminado");
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7","M5","M6","M8"})
    void invalidExternalReferenceRollsBackAndPermanentBusinessRejectionIsAcked(String area) throws Exception {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED); var event=externalEvent(area,ticket,"RESOLVED"); evidence(event);
        ((ObjectNode)data(event).path("attachments").get(0)).put("url","file:///etc/passwd");
        Channel channel=mock(Channel.class);
        new CoreEventConsumer(mock(ConnectionFactory.class),processor,1).receive(channel,mock(Connection.class),
                new Delivery(new Envelope(9,false,"",""),new AMQP.BasicProperties(),bytes(event)));
        verify(channel).basicAck(9,false); verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        assertThat(inbox.findById(id(event)).orElseThrow().getStatus()).isEqualTo(InboxStatus.FAILED);
        processor.process(bytes(event)); // terminal rejection is also idempotent
        assertThat(count("external_attachment_references",ticket)).isZero();
        assertThat(count("ticket_resolutions",ticket)).isZero();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7","M5","M6","M8"})
    void externalEvidenceAndBusinessEffectsRollbackTogetherWhenInboxCommitFails(String area) {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED); var event=externalEvent(area,ticket,"RESOLVED"); evidence(event); data(event).put("progress",100);
        InboxEventRepository failing=mock(InboxEventRepository.class,delegatesTo(inbox));
        doThrow(new DataAccessResourceFailureException("synthetic")).when(failing).saveAndFlush(any());
        var failed=new CoreInboxProcessor(validator,failing,tickets,updates,projection,clock);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> failed.process(bytes(event))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("external_attachment_references",ticket)).isZero();
        assertThat(count("ticket_resolutions",ticket)).isZero();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentProgress()).isNull();
        processor.process(bytes(event)); assertThat(count("external_attachment_references",ticket)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7","M5","M6","M8"})
    void otherAreaAndOldRoutingEpochCannotModifyExternalTicket(String area) {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        var wrongArea=track(status(ticket.getId(),"STARTED")); wrongArea.put("sourceModule",area.equals("M6")?"obras":"ambiente");
        processAndAssert(wrongArea,"IGNORED_OTHER_AREA");
        var old=externalEvent(area,ticket,"STARTED"); data(old).put("updateOccurredAt","2026-09-01T00:00:00Z");
        processAndAssert(old,"IGNORED_OLD_UPDATE");
        assertThat(count("ticket_activities",ticket)).isZero();
    }
    @ParameterizedTest @CsvSource({"M3,IN_PROGRESS","M4,IN_PROGRESS","M7,IN_PROGRESS","M5,IN_PROGRESS",
            "M6,IN_PROGRESS","M8,IN_PROGRESS","M8,ROUTED"})
    void externalInformationRequestPausesSlaAndCitizenAnswerPublishesInformationProvided(String area,TicketStatus initial) {
        Ticket ticket=externalTicket(area,initial);
        Instant now=clock.instant();
        transactions.executeWithoutResult(tx -> sla.startInitialResolutionCycle(
                tickets.findById(ticket.getId()).orElseThrow(),now.minusSeconds(60)).orElseThrow());
        var event=externalEvent(area,ticket,"INFORMATION_REQUIRED"); data(event).put("updateOccurredAt",now.toString());
        details(event).withObject("/informationRequest").put("requiredBy",now.plusSeconds(3600).toString());
        processor.process(bytes(event)); processor.process(bytes(event));
        assertThat(count("information_requests",ticket)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT paused_at IS NOT NULL FROM ticket_sla WHERE ticket_id=?",Boolean.class,ticket.getId())).isTrue();
        assertThat(count("outbox_events",ticket)).isZero();
        information.answerInformation(ticket.getId(),new com.reclamos.backend.dto.request.AnswerInformationRequest("Altura 123"),
                new AuthenticatedIdentity("test",ticket.getCitizenId(),"Test",null,ModuleRole.CITIZEN));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(initial);
        assertThat(db.queryForObject("SELECT paused_at IS NULL FROM ticket_sla WHERE ticket_id=?",Boolean.class,ticket.getId())).isTrue();
        assertThat(db.queryForObject("SELECT update_type FROM outbox_events WHERE ticket_id=?",String.class,ticket.getId()))
                .isEqualTo("INFORMATION_PROVIDED");
        assertThat(db.queryForObject("SELECT payload->'data'->>'responsibleAreaId' FROM outbox_events WHERE ticket_id=?",String.class,ticket.getId())).isEqualTo(area);
        if(initial==TicketStatus.ROUTED) {
            var premature=externalEvent(area,ticket,"PROGRESS"); data(premature).put("updateOccurredAt",clock.instant().toString());
            assertThatThrownBy(() -> processor.process(bytes(premature))).hasMessage("PREMATURE_PROGRESS");
            var resolved=externalEvent(area,ticket,"RESOLVED"); data(resolved).put("updateOccurredAt",clock.instant().toString());
            processor.process(bytes(resolved));
            assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
        }
    }
    @Test void m4OriginalResolvedFixtureCommitsContractualMessageAndActor() throws Exception {
        Ticket ticket=externalTicket("M4",TicketStatus.ROUTED);
        var event=(ObjectNode)JSON.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "docs/integrations/examples/m4/resolved-original.json")));
        event.put("eventId",UUID.randomUUID().toString());
        data(event).put("ticketId",ticket.getId().toString());
        // Original contractual date is later than this disposable ticket's routing epoch.
        track(event); processor.process(bytes(event)); processor.process(bytes(event));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(count("ticket_resolutions",ticket)).isEqualTo(1);
        assertThat(count("external_attachment_references",ticket)).isZero();
        assertThat(count("outbox_events",ticket)).isZero();
        assertThat(db.queryForObject("SELECT text FROM ticket_messages WHERE ticket_id=? AND visibility='PUBLIC'",String.class,ticket.getId()))
                .isEqualTo(data(event).get("publicMessage").asText());
        assertThat(db.queryForObject("SELECT actor_id FROM ticket_activities WHERE ticket_id=?",String.class,ticket.getId()))
                .isEqualTo(data(event).path("updatedBy").path("id").asText());
    }
    @ParameterizedTest @ValueSource(strings={"M4","M7","M5","M6","M8"})
    void externalAckObservesCommittedEvidenceAndPrematureProgressNacks(String area) throws Exception {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        var event=externalEvent(area,ticket,"RESOLVED"); evidence(event);
        Channel channel=mock(Channel.class);
        var consumer=new CoreEventConsumer(mock(ConnectionFactory.class),processor,1);
        doAnswer(i -> {
            assertThat(inbox.existsById(id(event))).isTrue();
            assertThat(count("external_attachment_references",ticket)).isEqualTo(1);
            assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
            return null;
        }).when(channel).basicAck(17,false);
        consumer.receive(channel,mock(Connection.class),new Delivery(new Envelope(17,false,"",""),new AMQP.BasicProperties(),bytes(event)));
        verify(channel).basicAck(17,false);
        var premature=externalEvent(area,externalTicket(area,TicketStatus.ROUTED),"PROGRESS");
        consumer.receive(channel,mock(Connection.class),new Delivery(new Envelope(18,false,"",""),new AMQP.BasicProperties(),bytes(premature)));
        verify(channel).basicNack(18,false,false);
        assertThat(inbox.existsById(id(premature))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"M4","M7","M5","M6","M8"})
    void externalEventCollisionAndOldProgressCannotOverwriteCommittedFact(String area) {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        var event=externalEvent(area,ticket,"STARTED"); processor.process(bytes(event));
        data(event).put("publicMessage","Changed");
        assertThatThrownBy(() -> processor.process(bytes(event))).hasMessage("EVENT_ID_COLLISION");
        var old=externalEvent(area,ticket,"PROGRESS"); data(old).put("updateOccurredAt","2026-09-30T12:00:00Z");
        processAndAssert(old,"IGNORED_OLD_UPDATE");
        processAndAssert(externalEvent(area,ticket,"STARTED"),"IGNORED_STATE_CONFLICT");
        Ticket other=externalTicket(area.equals("M6")?"M3":"M6",TicketStatus.ROUTED);
        processAndAssert(externalEvent(area,other,"STARTED"),"IGNORED_OTHER_AREA");
        assertThat(count("ticket_activities",ticket)).isEqualTo(1);
        assertThat(count("outbox_events",ticket)).isZero();
    }
    // M7 scenarios are synthetic local M2 compatibility checks, without bilateral approval.
    @ParameterizedTest @ValueSource(strings={"started","progress","information-required","returned","resolved","rejected"})
    void m7LocalIllustrativeFixturesCommitWithoutEcho(String type) throws Exception {
        Ticket ticket=externalTicket("M7",type.equals("progress")?TicketStatus.IN_PROGRESS:TicketStatus.ROUTED);
        var event=(ObjectNode)JSON.readTree(java.nio.file.Path.of(
                "docs/integrations/examples/m7/"+type+"-local.json").toFile());
        event.put("eventId",UUID.randomUUID().toString()); data(event).put("ticketId",ticket.getId().toString());
        track(event); processor.process(bytes(event)); processor.process(bytes(event));
        TicketStatus expected=switch(type) {
            case "started","progress" -> TicketStatus.IN_PROGRESS;
            case "information-required" -> TicketStatus.PENDING_INFORMATION;
            case "returned" -> TicketStatus.IN_REVIEW;
            case "resolved" -> TicketStatus.RESOLVED;
            default -> TicketStatus.CANCELLED;
        };
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(expected);
        assertThat(inbox.findById(id(event)).orElseThrow().getProducerModuleId()).isEqualTo("M7");
        assertThat(count("ticket_activities",ticket)).isEqualTo(1);
        assertThat(count("external_attachment_references",ticket)).isEqualTo(type.equals("progress")?1:0);
        assertThat(count("outbox_events",ticket)).isZero();
    }
    @ParameterizedTest @CsvSource({"M7,ciudadanos","M7,core","M7,unknown-producer",
            "M5,ciudadanos","M5,core","M5,unknown-producer","M6,ciudadanos","M6,core","M6,unknown-producer","M8,ciudadanos","M8,core","M8,unknown-producer"})
    void unsupportedProducerCannotModifyExternalTicket(String area,String producer) {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        var event=externalEvent(area,ticket,"STARTED"); event.put("sourceModule",producer);
        processAndAssert(event,"IGNORED_PRODUCER");
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(count("ticket_activities",ticket)).isZero();
    }
    // Synthetic scenarios derived from M2 v1.79 and M6 agreement prose; Sobres PDF unavailable.
    @ParameterizedTest @CsvSource({"ROUTED,EXTERNAL_USER","IN_PROGRESS,EXTERNAL_USER","ROUTED,SYSTEM","IN_PROGRESS,SYSTEM"})
    void m6ResolutionWithoutEvidenceAcceptsUnidentifiedActorAndNeverEchoes(String state,String actor) {
        Ticket ticket=externalTicket("M6",TicketStatus.valueOf(state));
        var event=externalEvent("M6",ticket,"RESOLVED");
        data(event).withObject("/updatedBy").put("type",actor).putNull("id");
        processor.process(bytes(event)); processor.process(bytes(event));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(count("ticket_resolutions",ticket)).isEqualTo(1);
        assertThat(count("external_attachment_references",ticket)).isZero();
        assertThat(count("outbox_events",ticket)).isZero();
        assertThat(db.queryForObject("SELECT actor_id FROM ticket_activities WHERE ticket_id=?",String.class,ticket.getId())).isNull();
        assertThat(db.queryForObject("SELECT text FROM ticket_messages WHERE ticket_id=? AND visibility='PUBLIC'",String.class,ticket.getId())).isEqualTo("Trabajo terminado");
    }
    @ParameterizedTest @ValueSource(strings={"OTHER","DOES_NOT_APPLY","REQUEST_TYPE_MISMATCH"})
    void m6CancellationAndReturnUseOfficialReasonsAndInternalExplanation(String reason) {
        Ticket ticket=externalTicket("M6",TicketStatus.ROUTED);
        boolean returned=reason.equals("REQUEST_TYPE_MISMATCH");
        var event=externalEvent("M6",ticket,returned?"RETURNED":"REJECTED");
        data(event).remove("publicMessage"); data(event).put("internalMessage","Motivo del operador");
        details(event).withObject(returned?"/returnInfo":"/cancellation").put("reasonCode",reason);
        processor.process(bytes(event)); processor.process(bytes(event));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(returned?TicketStatus.IN_REVIEW:TicketStatus.CANCELLED);
        assertThat(db.queryForObject("SELECT reason_code FROM ticket_activities WHERE ticket_id=?",String.class,ticket.getId())).isEqualTo(reason);
        assertThat(count("ticket_activities",ticket)).isEqualTo(1);
        assertThat(count("outbox_events",ticket)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"M6","M8"})
    void pastSuggestedDeadlineIsAuditedAndAckedAfterRollback(String area) throws Exception {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        var event=externalEvent(area,ticket,"INFORMATION_REQUIRED");
        details(event).withObject("/informationRequest").put("requiredBy","2000-01-01T00:00:00Z");
        Channel channel=mock(Channel.class);
        var consumer=new CoreEventConsumer(mock(ConnectionFactory.class),processor,1);
        var delivery=new Delivery(new Envelope(19,false,"",""),new AMQP.BasicProperties(),bytes(event));
        consumer.receive(channel,mock(Connection.class),delivery); consumer.receive(channel,mock(Connection.class),delivery);
        verify(channel,times(2)).basicAck(19,false);
        assertThat(inbox.findById(id(event)).orElseThrow().getError()).isEqualTo("REJECTED_BUSINESS_RULE");
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(count("information_requests",ticket)).isZero();
        assertThat(count("ticket_activities",ticket)).isZero();
        assertThat(count("outbox_events",ticket)).isZero();
    }
    // Editorial copies keep original values; only disposable identities and business-clock dates adapt here.
    @ParameterizedTest @CsvSource({"started,ROUTED","progress,IN_PROGRESS","information-required,ROUTED","returned,ROUTED",
            "resolved-fulfilled,ROUTED","resolved-fulfilled,IN_PROGRESS","resolved-no-further,ROUTED","resolved-no-further,IN_PROGRESS","rejected,ROUTED"})
    void m8PdfFactsPersistMessagesProgressReasonsAndTracesWithoutEcho(String name,TicketStatus initial) throws Exception {
        Ticket ticket=externalTicket("M8",initial);
        var event=m8Fixture(name,ticket);
        processor.process(bytes(event)); processor.process(bytes(event));
        var stored=tickets.findById(ticket.getId()).orElseThrow();
        TicketStatus expected=switch(name) {
            case "started","progress" -> TicketStatus.IN_PROGRESS;
            case "information-required" -> TicketStatus.PENDING_INFORMATION;
            case "returned" -> TicketStatus.IN_REVIEW;
            case "rejected" -> TicketStatus.CANCELLED;
            default -> TicketStatus.RESOLVED;
        };
        assertThat(stored.getCurrentStatus()).isEqualTo(expected);
        if(data(event).hasNonNull("progress"))
            assertThat(stored.getCurrentProgress()).isEqualTo((short)data(event).get("progress").asInt());
        String visibility=name.equals("returned")?"INTERNAL":"PUBLIC";
        assertThat(db.queryForObject("SELECT text FROM ticket_messages WHERE ticket_id=? AND visibility=?",String.class,ticket.getId(),visibility))
                .isEqualTo(data(event).get(name.equals("returned")?"internalMessage":"publicMessage").asText());
        var receipt=inbox.findById(id(event)).orElseThrow();
        assertThat(receipt.getProducerModuleId()).isEqualTo("M8");
        assertThat(receipt.getPayload()).containsEntry("correlationId",event.get("correlationId").asText())
                .containsEntry("causationId",event.get("causationId").asText()).containsEntry("disposition","APPLIED");
        assertThat(count("ticket_activities",ticket)).isEqualTo(1);
        assertThat(count("outbox_events",ticket)).isZero();
        if(name.startsWith("resolved")) {
            assertThat(db.queryForObject("SELECT resolution_type FROM ticket_resolutions WHERE ticket_id=?",String.class,ticket.getId()))
                    .isEqualTo(details(event).path("resolution").path("type").asText());
            assertThat(stored.getResolutionConfirmationDueAt()).isNotNull();
        }
        if(name.equals("returned")||name.equals("rejected"))
            assertThat(db.queryForObject("SELECT reason_code FROM ticket_activities WHERE ticket_id=?",String.class,ticket.getId()))
                    .isEqualTo(name.equals("returned")?"REQUEST_TYPE_MISMATCH":"DOES_NOT_APPLY");
    }

    @ParameterizedTest @ValueSource(strings={"PROGRESS","RESOLVED","CONFIRM"})
    void m8EvaluatedRefusalAllowsConfirmationOrReopeningWithoutAnotherStarted(String next) throws Exception {
        Ticket ticket=externalTicket("M8",TicketStatus.ROUTED);
        processor.process(bytes(m8Fixture("resolved-no-further",ticket)));
        var owner=new AuthenticatedIdentity("test",ticket.getCitizenId(),"Test",null,ModuleRole.CITIZEN);
        if(next.equals("CONFIRM")) {
            resolutions.confirm(ticket.getId(),owner);
            assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.CLOSED);
        } else {
            resolutions.reopen(ticket.getId(),new com.reclamos.backend.dto.request.ReopenTicketRequest("Solicito nueva evaluación"),owner);
            assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            var event=externalEvent("M8",ticket,next); data(event).put("updateOccurredAt",clock.instant().toString());
            processor.process(bytes(event));
            assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus())
                    .isEqualTo(next.equals("PROGRESS")?TicketStatus.IN_PROGRESS:TicketStatus.RESOLVED);
        }
        assertThat(count("ticket_cancellations",ticket)).isZero();
        assertThat(count("outbox_events",ticket)).isEqualTo(1); // only independent CLOSED or REOPENED
    }

    @ParameterizedTest @CsvSource({"M6,false","M6,true","M8,false","M8,true"})
    void informationTimeoutCancelsUnlessMainHasActiveDuplicates(String area,boolean hasDuplicates) {
        Ticket main=externalTicket(area,TicketStatus.IN_PROGRESS);
        if(hasDuplicates) {
            Ticket child=externalTicket(area,TicketStatus.DUPLICATE);
            db.update("UPDATE tickets SET main_ticket_id=? WHERE id=?",main.getId(),child.getId());
        }
        Instant now=clock.instant();
        transactions.executeWithoutResult(tx -> sla.startInitialResolutionCycle(tickets.findById(main.getId()).orElseThrow(),now.minusSeconds(60)));
        var event=externalEvent(area,main,"INFORMATION_REQUIRED"); data(event).put("updateOccurredAt",now.toString());
        processor.process(bytes(event));
        UUID request=db.queryForObject("SELECT id FROM information_requests WHERE ticket_id=?",UUID.class,main.getId());
        db.update("UPDATE information_requests SET requested_at=now()-interval '1 day',due_at=now()-interval '1 second' WHERE id=?",request);
        expiration.expireIfDue(request,main.getId(),clock.instant());
        assertThat(db.queryForObject("SELECT status FROM information_requests WHERE id=?",String.class,request)).isEqualTo("EXPIRED");
        assertThat(tickets.findById(main.getId()).orElseThrow().getCurrentStatus())
                .isEqualTo(hasDuplicates?TicketStatus.IN_REVIEW:TicketStatus.CANCELLED);
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=? AND update_type='CANCELLED'",Integer.class,main.getId()))
                .isEqualTo(hasDuplicates?0:1);
        assertThat(count("ticket_cancellations",main)).isEqualTo(hasDuplicates?0:1);
        if(hasDuplicates) {
            assertThat(db.queryForObject("SELECT count(*) FROM tickets WHERE main_ticket_id=? AND current_status='DUPLICATE'",Integer.class,main.getId())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT paused_at IS NULL AND completed_at IS NULL FROM ticket_sla WHERE ticket_id=?",Boolean.class,main.getId())).isTrue();
        }
    }

    private ObjectNode m8Fixture(String name,Ticket ticket) throws Exception {
        var event=(ObjectNode)JSON.readTree(java.nio.file.Path.of("docs/integrations/examples/m8/"+name+"-normalizado.json").toFile());
        event.put("eventId",UUID.randomUUID().toString()); data(event).put("ticketId",ticket.getId().toString());
        Instant now=clock.instant(); event.put("occurredAt",now.toString()); data(event).put("updateOccurredAt",now.toString());
        if(name.equals("information-required")) details(event).withObject("/informationRequest").put("requiredBy",now.plusSeconds(3600).toString());
        return track(event);
    }

    private Ticket externalTicket(String area,TicketStatus state) {
        Ticket ticket=ticket(state); ticket.setResponsibleAreaId(area); return tickets.saveAndFlush(ticket);
    }
    private ObjectNode externalEvent(String area,Ticket ticket,String type) {
        var event=track(status(ticket.getId(),type)); event.put("sourceModule",switch(area) {
            case "M5" -> "rentas"; case "M3" -> "obras"; case "M4" -> "habilitaciones"; case "M6" -> "ambiente"; case "M7" -> "transito"; case "M8" -> "desarrollo-social";
            default -> throw new IllegalArgumentException("Unsupported test area: "+area);
        }); return event;
    }
    private void evidence(ObjectNode event) {
        data(event).putArray("attachments").addObject().put("fileName","obra.jpg")
                .put("contentType","image/jpeg").put("url","https://external.invalid/photos/1");
    }
    @Test void sameEventIdWithDifferentContentIsRejectedWithoutChangingTheCommittedFact() {
        UUID citizen=citizenId(); var event=track(citizen(citizen,"REGISTERED")); details(event).put("name","Original");
        processor.process(bytes(event)); details(event).put("name","Changed");
        assertThatThrownBy(() -> processor.process(bytes(event))).hasMessage("EVENT_ID_COLLISION");
        assertThat(users.findByActorId(citizen).orElseThrow().getFirstName()).isEqualTo("Original");
    }
    @ParameterizedTest @ValueSource(strings={"M6","M7","M5","M8"})
    void simultaneousDuplicateDeliveriesCommitExactlyOneSetOfEffects(String area) throws Exception {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED); var event=externalEvent(area,ticket,"STARTED");
        byte[] body=bytes(event); CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Void> work=() -> { barrier.await(5,TimeUnit.SECONDS); processor.process(body); return null; };
            var one=executor.submit(work); var two=executor.submit(work);
            one.get(10,TimeUnit.SECONDS); two.get(10,TimeUnit.SECONDS);
        }
        assertThat(count("ticket_activities",ticket)).isEqualTo(1); assertThat(inbox.existsById(id(event))).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"M6","M7","M5","M8"})
    void independentSlaFactKeepsIncomingCorrelationAndUsesIncomingEventAsCausation(String area) {
        Ticket ticket=externalTicket(area,TicketStatus.ROUTED);
        transactions.executeWithoutResult(tx -> {
            var managed=tickets.findById(ticket.getId()).orElseThrow();
            sla.startInitialResolutionCycle(managed,managed.getCreatedAt());
        });
        db.update("UPDATE ticket_sla SET status='BREACHED',started_at=now()-interval '300 seconds',near_due_at=now()-interval '200 seconds',due_at=now()-interval '100 seconds' WHERE ticket_id=?",ticket.getId());
        db.update("UPDATE tickets SET is_escalated=true,escalation_reason_code='SLA_BREACHED',escalated_at=now()-interval '100 seconds' WHERE id=?",ticket.getId());
        var event=externalEvent(area,ticket,"RESOLVED"); UUID correlation=UUID.randomUUID();
        event.put("correlationId",correlation.toString()).put("causationId",UUID.randomUUID().toString());
        data(event).put("updateOccurredAt",Instant.now().toString());
        processor.process(bytes(event));
        assertThat(db.queryForObject("SELECT update_type FROM outbox_events WHERE ticket_id=?",String.class,ticket.getId())).isEqualTo("ESCALATION_CHANGED");
        assertThat(db.queryForMap("SELECT payload->>'correlationId' AS correlation,payload->>'causationId' AS causation FROM outbox_events WHERE ticket_id=?",ticket.getId()))
                .containsEntry("correlation",correlation.toString()).containsEntry("causation",id(event).toString());
    }
    @ParameterizedTest @ValueSource(strings={"M6","M7","M5","M8"})
    void rejectedMainWithDuplicatesCommitsExistingScopeReviewAuditAndAnIgnoredReceipt(String area) {
        Ticket main=externalTicket(area,TicketStatus.ROUTED),child=ticket(TicketStatus.DUPLICATE);
        db.update("UPDATE tickets SET main_ticket_id=? WHERE id=?",main.getId(),child.getId());
        var event=externalEvent(area,main,"REJECTED");
        processAndAssert(event,"IGNORED_CANCELLATION_SCOPE_REVIEW"); processor.process(bytes(event));
        assertThat(tickets.findById(main.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.ROUTED);
        assertThat(tickets.findById(child.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.DUPLICATE);
        assertThat(count("ticket_activities",main)).isEqualTo(1); assertThat(count("ticket_cancellations",main)).isZero();
    }
    @Test void committedDeliveryWithLostAckIsDeduplicatedByAReplacementConsumer() throws Exception {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        var event = track(status(ticket.getId(), "STARTED"));
        byte[] body = bytes(event);
        Channel oldChannel = mock(Channel.class), newChannel = mock(Channel.class);
        Connection oldConnection = mock(Connection.class), newConnection = mock(Connection.class);
        var oldConsumer = new CoreEventConsumer(mock(ConnectionFactory.class), processor, 1);
        doThrow(new java.io.IOException("shutdown before ACK reached broker")).when(oldChannel).basicAck(7, false);
        oldConsumer.receive(oldChannel, oldConnection,
                new Delivery(new Envelope(7, false, "", ""), new AMQP.BasicProperties(), body));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(count("ticket_activities", ticket)).isEqualTo(1);
        verify(oldConnection).abort(1000);
        var replacement = new CoreEventConsumer(mock(ConnectionFactory.class), processor, 1);
        replacement.receive(newChannel, newConnection,
                new Delivery(new Envelope(1, true, "", ""), new AMQP.BasicProperties(), body));
        verify(newChannel).basicAck(1, false);
        verify(newChannel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(count("ticket_activities", ticket)).isEqualTo(1);
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(inbox.findById(id(event))).isPresent();
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

    @Test void duplicateDeliveryAcksWithoutRewritingReceiptOrRepeatingAnyBusinessEffects() throws Exception {
        Ticket ticket = ticket(TicketStatus.ROUTED);
        var event = track(status(ticket.getId(), "RESOLVED")); evidence(event);
        Channel channel = mock(Channel.class);
        var consumer = new CoreEventConsumer(mock(ConnectionFactory.class), processor, 1);
        consumer.receive(channel, mock(Connection.class), delivery(41, false, event));
        var first = db.queryForMap("SELECT * FROM inbox_events WHERE event_id=?", id(event));
        consumer.receive(channel, mock(Connection.class), delivery(42, true, event));
        verify(channel).basicAck(41, false); verify(channel).basicAck(42, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(db.queryForMap("SELECT * FROM inbox_events WHERE event_id=?", id(event))).isEqualTo(first);
        for (String table : List.of("ticket_activities", "ticket_messages", "ticket_resolutions", "external_attachment_references"))
            assertThat(count(table, ticket)).as(table).isEqualTo(1);
        assertThat(count("outbox_events", ticket)).isZero();
        // A valid but different body with the same id must not be mistaken for a duplicate.
        data(event).put("publicMessage", "different synthetic message");
        consumer.receive(channel, mock(Connection.class), delivery(43, true, event));
        verify(channel).basicNack(43, false, false);
        assertThat(db.queryForMap("SELECT * FROM inbox_events WHERE event_id=?", id(event))).isEqualTo(first);
        assertThat(count("ticket_messages", ticket)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void consumerWaitsForRealCommitAndNacksIfCommitFails(boolean failCommit) throws Exception {
        UUID citizen = citizenId(); var event = track(citizen(citizen, "REGISTERED"));
        Channel channel = mock(Channel.class);
        // Wrap the real Spring processor in a transaction whose final commit we can pause/fail.
        CoreInboxProcessor transactionBoundary = mock(CoreInboxProcessor.class);
        doAnswer(call -> {
            transactions.executeWithoutResult(tx -> {
                processor.process(bytes(event));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        try {
                            verify(channel, never()).basicAck(anyLong(), anyBoolean());
                            // Independent JDBC connection cannot see the uncommitted receipt.
                            try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection();
                                 var query = connection.prepareStatement("SELECT count(*) FROM inbox_events WHERE event_id=?")) {
                                query.setObject(1, id(event));
                                try (var rows = query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
                            }
                        } catch (Exception e) { throw new AssertionError(e); }
                        if (failCommit) throw new DataAccessResourceFailureException("synthetic commit failure");
                    }
                    @Override public void afterCommit() {
                        try { verify(channel, never()).basicAck(anyLong(), anyBoolean()); }
                        catch (Exception e) { throw new AssertionError(e); }
                    }
                });
            });
            return null;
        }).when(transactionBoundary).process(any());
        new CoreEventConsumer(mock(ConnectionFactory.class), transactionBoundary, 1)
                .receive(channel, mock(Connection.class), delivery(44, false, event));
        if (failCommit) {
            verify(channel).basicNack(44, false, false); verify(channel, never()).basicAck(anyLong(), anyBoolean());
            assertThat(inbox.existsById(id(event))).isFalse(); assertThat(users.findByActorId(citizen)).isEmpty();
            new CoreEventConsumer(mock(ConnectionFactory.class), processor, 1)
                    .receive(channel, mock(Connection.class), delivery(45, true, event));
            verify(channel).basicAck(45, false);
        } else verify(channel).basicAck(44, false);
        assertThat(inbox.existsById(id(event))).isTrue(); assertThat(users.findByActorId(citizen)).isPresent();
    }

    @Test void invalidEnvelopeNacksWithoutReceiptAndTerminalRejectionAuditsOnlySafeMetadata() throws Exception {
        Ticket ticket = ticket(TicketStatus.ROUTED); var event = track(status(ticket.getId(), "RESOLVED"));
        evidence(event); String secret = "private-message-token";
        data(event).put("internalMessage", secret);
        ((ObjectNode)data(event).path("attachments").get(0)).put("url", "file:///private-address?token=" + secret);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CoreInboxProcessor.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var consumerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CoreEventConsumer.class);
        capture.start(); logger.addAppender(capture); consumerLogger.addAppender(capture);
        Channel channel = mock(Channel.class);
        var consumer = new CoreEventConsumer(mock(ConnectionFactory.class), processor, 1);
        try {
            consumer.receive(channel, mock(Connection.class), delivery(46, false, event));
            verify(channel).basicAck(46, false);
            var receipt = inbox.findById(id(event)).orElseThrow();
            assertThat(receipt.getStatus()).isEqualTo(InboxStatus.FAILED);
            assertThat(receipt.getError()).isEqualTo("REJECTED_BUSINESS_RULE");
            assertThat(receipt.getPayload()).containsOnlyKeys("sourceModule", "occurredAt", "disposition");
            assertThat(receipt.toString()).doesNotContain(secret, "file:///", "internalMessage");
            assertThat(count("ticket_messages", ticket)).isZero();
            assertThat(count("ticket_resolutions", ticket)).isZero();
            assertThat(count("external_attachment_references", ticket)).isZero();
            consumer.receive(channel, mock(Connection.class), delivery(47, true, event));
            verify(channel).basicAck(47, false);
            event.put("eventVersion", "unsupported");
            consumer.receive(channel, mock(Connection.class), delivery(48, false, event));
            verify(channel).basicNack(48, false, false);
            var malformed = track(status(ticket.getId(), "STARTED")); UUID malformedId = id(malformed);
            malformed.put("eventId", "invalid");
            consumer.receive(channel, mock(Connection.class), delivery(49, false, malformed));
            verify(channel).basicNack(49, false, false);
            assertThat(inbox.existsById(malformedId)).isFalse();
            UUID citizen = citizenId(); var projected = track(citizen(citizen, "ADDRESS_UPDATED"));
            details(projected).put("dni", secret).put("cuil", secret).putObject("newAddress").put("street", secret);
            consumer.receive(channel, mock(Connection.class), delivery(50, false, projected));
            verify(channel).basicAck(50, false);
            var projectedReceipt = inbox.findById(id(projected)).orElseThrow();
            assertThat(projectedReceipt.getPayload()).containsOnlyKeys("sourceModule", "occurredAt", "correlationId", "causationId", "disposition");
            assertThat(projectedReceipt.toString()).doesNotContain(secret, "dni", "cuil", "street");
            assertThat(capture.list).isNotEmpty().allSatisfy(log -> {
                assertThat(log.getFormattedMessage()).doesNotContain(secret, "file:///", "internalMessage");
                assertThat(log.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(capture); consumerLogger.detachAppender(capture); capture.stop(); }
    }

    private Delivery delivery(long tag, boolean redelivered, ObjectNode event) {
        return new Delivery(new Envelope(tag, redelivered, "", ""), new AMQP.BasicProperties(), bytes(event));
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
