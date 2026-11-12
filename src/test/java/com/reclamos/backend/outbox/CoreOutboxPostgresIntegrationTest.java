package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.repository.*;
import com.reclamos.backend.service.TicketSlaMilestoneService;
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

import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.reclamos.backend.outbox.OutboxFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real commits/row locks on disposable PostgreSQL; the AMQP boundary is always mocked. */
@SpringBootTest(properties = {
        "app.core.outbox.enabled=true", "app.core.outbox.host=rabbit.example.test", "app.core.outbox.port=5671",
        "app.core.outbox.virtual-host=core", "app.core.outbox.username=synthetic-user", "app.core.outbox.password=synthetic-password",
        "app.core.outbox.approved-event-ids=6c5a4e23-1482-4a2d-8c3b-c713011e0001,6c5a4e23-1482-4a2d-8c3b-c713011e0002",
        "app.core.outbox.max-attempts=3", "app.core.outbox.retry-delay=15s", "app.core.outbox.confirm-timeout=1s",
        "app.core.outbox.connect-timeout=1s", "app.core.outbox.scan-delay=3600000", "spring.jpa.show-sql=false",
        "ticket.information-request.expiration-scan-delay=3600000", "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000", "notification.pending-scan-delay=3600000", "ticket.priority.scan-delay=3600000"
})
@ActiveProfiles("dev")
class CoreOutboxPostgresIntegrationTest {
    private static final UUID FIRST = UUID.fromString("6c5a4e23-1482-4a2d-8c3b-c713011e0001");
    private static final UUID SECOND = UUID.fromString("6c5a4e23-1482-4a2d-8c3b-c713011e0002");
    @Autowired CoreOutboxProcessor processor;
    @Autowired OutboxEventRepository events;
    @Autowired TicketRepository tickets;
    @Autowired ModuleUserRepository users;
    @Autowired RequestTypeRepository types;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate db;
    @Autowired TicketSlaMilestoneService milestones;
    @Autowired OutgoingEventValidator validator;
    @MockitoBean(enforceOverride = true) CoreEventPublisher publisher;
    private final List<UUID> ticketIds = new ArrayList<>();
    private final List<UUID> citizenIds = new ArrayList<>();

    @BeforeEach void resetPublisher() { reset(publisher); }
    @AfterEach void cleanup() {
        for (UUID id : ticketIds) {
            db.update("DELETE FROM outbox_events WHERE ticket_id=?", id);
            db.update("DELETE FROM ticket_activities WHERE ticket_id=?", id);
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        for (UUID id : citizenIds) db.update("DELETE FROM module_users WHERE citizen_id=?", id);
    }

    @Test void enabledPublisherOnlyDeliversSelectedRowsAndPreservesHistoricalPendingEvents() {
        var historical = fixture(UUID.randomUUID(), "ticketCreated", IdentitySource.MOCK);
        db.update("UPDATE outbox_events SET created_at='2020-01-01T00:00:00Z' WHERE id=?", historical.getId());
        var approved = fixture(FIRST, "ticketCreated", IdentitySource.M1);
        assertThat(processor.processNextApproved()).isTrue();
        assertThat(processor.processNextApproved()).isFalse();
        var published = events.findById(approved.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
        var unchanged = events.findById(historical.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(unchanged.getRetryCount()).isZero();
        assertThat(unchanged.getCreatedAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(unchanged.getPublishedAt()).isNull();
        verify(publisher).publish(eq(FIRST), eq("ticketCreated"), any());
    }

    @Test void retryScheduleSurvivesServiceReplacementAndReusesTheStoredEvent() {
        var event = fixture(FIRST, "ticketUpdated", IdentitySource.M1);
        doThrow(new PublicationFailure(PublicationFailure.Code.CONFIRM_TIMEOUT)).doNothing()
                .when(publisher).publish(any(), any(), any());
        processor.processNextApproved();
        var failedAttempt = events.findById(event.getId()).orElseThrow();
        assertThat(failedAttempt.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failedAttempt.getRetryCount()).isEqualTo(1);
        assertThat(failedAttempt.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(processor.processNextApproved()).isFalse();
        db.update("UPDATE outbox_events SET next_attempt_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?", event.getId());
        new CoreOutboxWorker(processor).publishNext();
        var delivered = events.findById(event.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(delivered.getRetryCount()).isEqualTo(2);
        assertThat(delivered.getPayload()).isEqualTo(event.getPayload());
        verify(publisher, times(2)).publish(eq(FIRST), eq("ticketUpdated"), any());
    }

    @Test void concurrentWorkersSkipTheLockedRowAndNeverPublishItSimultaneously() throws Exception {
        fixture(FIRST, "ticketCreated", IdentitySource.M1);
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
        doAnswer(invocation -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max); entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("local test latch timeout"); }
            finally { active.decrementAndGet(); }
            return null;
        }).when(publisher).publish(any(), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(processor::processNextApproved);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(processor::processNextApproved).get(3, TimeUnit.SECONDS)).isFalse();
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(maximum.get()).isEqualTo(1);
        verify(publisher, times(1)).publish(eq(FIRST), any(), any());
    }

    @Test void crashBeforeConfirmationRollsBackAndANewWorkerCanRecoverWithoutLeaseCleanup() {
        var event = fixture(FIRST, "ticketCreated", IdentitySource.M1);
        doThrow(new AssertionError("simulated process crash")).when(publisher).publish(any(), any(), any());
        assertThatThrownBy(processor::processNextApproved).isInstanceOf(AssertionError.class);
        var pending = events.findById(event.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.getRetryCount()).isZero();
        doNothing().when(publisher).publish(any(), any(), any());
        assertThat(processor.processNextApproved()).isTrue();
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test void crashAfterAckBeforeCommitMayDuplicateButAlwaysWithSameEnvelopeAndEventId() {
        var event = fixture(FIRST, "ticketUpdated", IdentitySource.M1);
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("simulated commit failure"); }
            });
            return null;
        }).when(publisher).publish(any(), any(), any());
        assertThatThrownBy(processor::processNextApproved).isInstanceOf(IllegalStateException.class);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
        doNothing().when(publisher).publish(any(), any(), any());
        processor.processNextApproved();
        var bodies = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(publisher, times(2)).publish(eq(FIRST), eq("ticketUpdated"), bodies.capture());
        assertThat(bodies.getAllValues().get(0)).isEqualTo(bodies.getAllValues().get(1));
    }

    @Test void selectedMockAndInvalidSnapshotsAreQuarantinedWithoutSendingOrChangingTheirIds() {
        var mock = fixture(FIRST, "ticketCreated", IdentitySource.MOCK);
        var invalid = fixture(SECOND, "ticketUpdated", IdentitySource.M1);
        transactions.executeWithoutResult(ignored -> {
            var managed = events.findById(invalid.getId()).orElseThrow();
            var payload = new LinkedHashMap<>(managed.getPayload()); payload.put("internalMessage", "private"); managed.setPayload(payload);
        });
        processor.processNextApproved(); processor.processNextApproved();
        assertThat(events.findById(mock.getId()).orElseThrow().getLastFailureCode()).isEqualTo("UNVERIFIED_IDENTITY");
        assertThat(events.findById(invalid.getId()).orElseThrow().getLastFailureCode()).isEqualTo("INVALID_PAYLOAD");
        assertThat(events.findById(mock.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(events.findById(invalid.getId()).orElseThrow().getPublishedAt()).isNull();
        verifyNoInteractions(publisher);
    }

    @Test void actualSlaProducerAnonymousEscalationCanBeConfirmedAndCommitted() {
        var initial = fixture(FIRST, "ticketUpdated", IdentitySource.MOCK, true);
        Long escalationId = transactions.execute(ignored -> {
            events.deleteById(initial.getId()); events.flush();
            var ticket = tickets.findById(initial.getTicket().getId()).orElseThrow();
            ticket.setCurrentStatus(TicketStatus.IN_PROGRESS); ticket.setCurrentPriority(Priority.CRITICAL);
            ticket.setClassificationFinalizedAt(Instant.now());
            milestones.reconcileEscalation(ticket, Instant.now(), true);
            events.flush();
            return db.queryForObject("SELECT id FROM outbox_events WHERE ticket_id=? AND update_type='ESCALATION_CHANGED'",
                    Long.class, ticket.getId());
        });
        var escalation = events.findById(escalationId).orElseThrow();
        UUID originalEventId = escalation.getEventId();
        var snapshot = escalation.getPayload();
        // Select the UUID generated by the real SLA producer; no identifiers are rewritten.
        var selectedProcessor = new CoreOutboxProcessor(events, validator, publisher,
                settings(Set.of(originalEventId)), Clock.systemUTC(), mock(CorePublicationPolicy.class));
        Boolean processed = transactions.execute(ignored -> selectedProcessor.processNextApproved());
        assertThat(processed).isTrue();
        var published = events.findById(escalationId).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getEventId()).isEqualTo(originalEventId);
        assertThat(published.getPayload()).isEqualTo(snapshot);
        assertThat(data(published)).containsEntry("isAnonymous", true).containsEntry("citizenId", null);
        var body = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(publisher).publish(eq(originalEventId), eq("ticketUpdated"), body.capture());
        assertThat(new String(body.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("synthetic-private-contact", "synthetic-anonymous-password");
    }

    @Test void publicFormAnswersArePublishedUnchangedWithoutExposingTechnicalTicketCredentials() throws Exception {
        var event = fixture(FIRST, "ticketCreated", IdentitySource.M1);
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("sessions", 3); answers.put("session", null); answers.put("cookie", "AVENA");
        transactions.executeWithoutResult(ignored -> {
            var managed = events.findById(event.getId()).orElseThrow();
            var payload = new LinkedHashMap<>(managed.getPayload());
            var data = new LinkedHashMap<>(data(managed)); data.put("formData", answers); payload.put("data", data);
            managed.setPayload(payload);
        });
        assertThat(processor.processNextApproved()).isTrue();
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        var body = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(publisher).publish(eq(FIRST), eq("ticketCreated"), body.capture());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(body.getValue()).path("data").path("formData")).isEqualTo(json.valueToTree(answers));
        assertThat(new String(body.getValue(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("trackingCodeHash");
    }

    private OutboxEvent fixture(UUID approved, String type, IdentitySource source) {
        return fixture(approved, type, source, false);
    }

    private OutboxEvent fixture(UUID approved, String type, IdentitySource source, boolean anonymous) {
        return transactions.execute(ignored -> {
            OutboxEvent event = event(type);
            Ticket ticket = event.getTicket(); ticket.setId(null);
            if (anonymous) {
                ticket.setAnonymous(true); ticket.setCitizenId(null);
                ticket.setAnonymousAccessPasswordHash("synthetic-anonymous-password");
                ticket.setAnonymousContactChannel(AnonymousContactChannel.EMAIL);
                ticket.setAnonymousContactValue("synthetic-private-contact@example.test");
                data(event).put("isAnonymous", true); data(event).put("citizenId", null);
            } else {
                var user = realCitizen(event); user.setIdentitySource(source); users.saveAndFlush(user); citizenIds.add(user.getCitizenId());
            }
            ticket.setPublicId("TK-2026-%06d".formatted(db.queryForObject("SELECT nextval('ticket_public_id_seq')", Long.class)));
            ticket.setTrackingCodeHash("synthetic-" + UUID.randomUUID());
            RequestType requestType = types.findAll().stream().filter(RequestType::isActive).findFirst().orElseThrow();
            ticket.setRequestType(requestType); ticket.setTicketType(requestType.getTicketType());
            ticket.setSummary("Synthetic case"); ticket.setDescription("Disposable integration test");
            ticket.setCurrentStatus(TicketStatus.REGISTERED); ticket.setCurrentPriority(Priority.MEDIUM);
            ticket.setStatusChangedAt(NOW); tickets.saveAndFlush(ticket); ticketIds.add(ticket.getId());
            data(event).put("ticketId", ticket.getId().toString()); data(event).put("publicId", ticket.getPublicId());
            event.setId(null); event.setEventId(approved); event.getPayload().put("eventId", approved.toString());
            return events.saveAndFlush(event);
        });
    }
}
