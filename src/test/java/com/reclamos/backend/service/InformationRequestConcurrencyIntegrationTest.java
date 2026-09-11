package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.InformationRequestConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "ticket.information-request.expiration-scan-delay=3600000")
@ActiveProfiles("dev")
@Import(InformationRequestConcurrencyIntegrationTest.TestClockConfiguration.class)
class InformationRequestConcurrencyIntegrationTest {
    private static final Instant DEADLINE = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID CITIZEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Autowired
    private InformationRequestService informationRequestService;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TicketActivityRepository activityRepository;
    @Autowired
    private RequestTypeRepository requestTypeRepository;
    @Autowired
    private JdbcTemplate database;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ThreadAwareClock clock;

    @Test
    void answerAndExpirationUseTheSameTicketFirstLockOrderWithoutDeadlock() throws Exception {
        Ticket ticket = createPendingInformationTicket();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (Connection blocker = lockTicket(ticket.getId())) {
            int blockerPid = backendPid(blocker);
            CountDownLatch answerStarted = new CountDownLatch(1);
            Future<Throwable> answer = executor.submit(() -> runAt(
                    DEADLINE.minusSeconds(1), answerStarted,
                    () -> informationRequestService.answerInformation(ticket.getId(),
                            new AnswerInformationRequest("Respuesta concurrente"), citizen())));
            assertTrue(answerStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedBy(blockerPid, 1);

            CountDownLatch expirationStarted = new CountDownLatch(1);
            Future<Throwable> expiration = executor.submit(() -> runAt(
                    DEADLINE.plusSeconds(1), expirationStarted,
                    informationRequestService::expireDueRequests));
            assertTrue(expirationStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedBy(blockerPid, 2);

            blocker.commit();

            assertNull(answer.get(15, TimeUnit.SECONDS));
            assertNull(expiration.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertAnsweredOutcome(ticket.getId());
        assertDoesNotThrow(() -> transactions.executeWithoutResult(status ->
                assertEquals(TicketStatus.IN_PROGRESS,
                        ticketRepository.findById(ticket.getId()).orElseThrow().getCurrentStatus())));
        cleanup(ticket.getId());
    }

    @Test
    void expirationWinningTheSameRaceLeavesAControlledTerminalState() throws Exception {
        Ticket ticket = createPendingInformationTicket();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (Connection blocker = lockTicket(ticket.getId())) {
            int blockerPid = backendPid(blocker);
            CountDownLatch expirationStarted = new CountDownLatch(1);
            Future<Throwable> expiration = executor.submit(() -> runAt(
                    DEADLINE.plusSeconds(1), expirationStarted,
                    informationRequestService::expireDueRequests));
            assertTrue(expirationStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedBy(blockerPid, 1);

            CountDownLatch answerStarted = new CountDownLatch(1);
            Future<Throwable> answer = executor.submit(() -> runAt(
                    DEADLINE.minusSeconds(1), answerStarted,
                    () -> informationRequestService.answerInformation(ticket.getId(),
                            new AnswerInformationRequest("Respuesta tardía"), citizen())));
            assertTrue(answerStarted.await(5, TimeUnit.SECONDS));
            awaitBlockedBy(blockerPid, 2);

            blocker.commit();

            assertNull(expiration.get(15, TimeUnit.SECONDS));
            assertInstanceOf(InformationRequestConflictException.class, answer.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertExpiredOutcome(ticket.getId());
        assertDoesNotThrow(() -> transactions.executeWithoutResult(status ->
                assertEquals(TicketStatus.CANCELLED,
                        ticketRepository.findById(ticket.getId()).orElseThrow().getCurrentStatus())));
        cleanup(ticket.getId());
    }

    private Ticket createPendingInformationTicket() {
        clock.setDefault(DEADLINE.minus(Duration.ofDays(3)));
        RequestType requestType = requestTypeRepository.findAll().stream()
                .filter(RequestType::isActive)
                .findFirst()
                .orElseThrow();
        Ticket ticket = new Ticket();
        ticket.setPublicId("OP-RACE-" + UUID.randomUUID().toString().substring(0, 12));
        ticket.setTrackingCodeHash("race-" + UUID.randomUUID());
        ticket.setCitizenId(CITIZEN_ID);
        ticket.setAnonymous(false);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary("Prueba de concurrencia");
        ticket.setDescription("Ticket temporal para validar el orden de locks");
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCurrentPriority(requestType.getMinimumPriority());
        ticket.setStatusChangedAt(clock.instant());
        ticket = ticketRepository.saveAndFlush(ticket);

        TicketActivity existing = new TicketActivity();
        existing.setTicket(ticket);
        existing.setSequence(1);
        existing.setActionType(ActivityType.STATE_CHANGED);
        existing.setPreviousStatus(TicketStatus.ROUTED);
        existing.setNewStatus(TicketStatus.IN_PROGRESS);
        existing.setActorType(ActorType.AGENT);
        existing.setActorId(UUID.randomUUID().toString());
        existing.setSourceModuleId("M2");
        existing.setOccurredAt(clock.instant());
        activityRepository.saveAndFlush(existing);

        informationRequestService.requestInformation(ticket.getId(),
                new CreateInformationRequest("Aporte información", null), agent());
        return ticket;
    }

    private Connection lockTicket(UUID ticketId) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM tickets WHERE id=? FOR UPDATE")) {
            statement.setObject(1, ticketId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
            }
        }
        return connection;
    }

    private int backendPid(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private void awaitBlockedBy(int blockerPid, int expected) {
        Instant timeout = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(timeout)) {
            Integer blocked = database.queryForObject(
                    "WITH RECURSIVE blocked_chain AS ("
                            + "SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)) "
                            + "UNION SELECT activity.pid FROM pg_stat_activity activity "
                            + "JOIN blocked_chain blocker ON blocker.pid = ANY(pg_blocking_pids(activity.pid))) "
                            + "SELECT COUNT(*) FROM blocked_chain", Integer.class, blockerPid);
            if (blocked != null && blocked >= expected) return;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        fail("No se observaron " + expected + " transacciones bloqueadas sobre el ticket");
    }

    private Throwable runAt(Instant instant, CountDownLatch started, ThrowingOperation operation) {
        clock.setForCurrentThread(instant);
        started.countDown();
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        } finally {
            clock.clearCurrentThread();
        }
    }

    private void assertAnsweredOutcome(UUID ticketId) {
        assertEquals("ANSWERED", scalar("SELECT status FROM information_requests WHERE ticket_id=?", ticketId));
        assertEquals("IN_PROGRESS", scalar("SELECT current_status FROM tickets WHERE id=?", ticketId));
        assertEquals(0, count("SELECT COUNT(*) FROM ticket_cancellations WHERE ticket_id=?", ticketId));
        assertEquals(0, count("SELECT COUNT(*) FROM ticket_activities "
                + "WHERE ticket_id=? AND action_type='CANCELLED'", ticketId));
        assertEquals(1, count("SELECT COUNT(*) FROM ticket_activities "
                + "WHERE ticket_id=? AND action_type='INFORMATION_PROVIDED'", ticketId));
        assertOrderedUniqueSequences(ticketId);
    }

    private void assertExpiredOutcome(UUID ticketId) {
        assertEquals("EXPIRED", scalar("SELECT status FROM information_requests WHERE ticket_id=?", ticketId));
        assertEquals("CANCELLED", scalar("SELECT current_status FROM tickets WHERE id=?", ticketId));
        assertEquals(1, count("SELECT COUNT(*) FROM ticket_cancellations WHERE ticket_id=?", ticketId));
        assertEquals(1, count("SELECT COUNT(*) FROM ticket_activities "
                + "WHERE ticket_id=? AND action_type='CANCELLED'", ticketId));
        assertEquals(0, count("SELECT COUNT(*) FROM ticket_activities "
                + "WHERE ticket_id=? AND action_type='INFORMATION_PROVIDED'", ticketId));
        assertOrderedUniqueSequences(ticketId);
    }

    private void assertOrderedUniqueSequences(UUID ticketId) {
        List<Integer> sequences = database.queryForList(
                "SELECT sequence FROM ticket_activities WHERE ticket_id=? ORDER BY sequence",
                Integer.class, ticketId);
        assertEquals(List.of(1, 2, 3), sequences);
        assertEquals(sequences.size(), sequences.stream().distinct().count());
    }

    private String scalar(String sql, UUID ticketId) {
        return database.queryForObject(sql, String.class, ticketId);
    }

    private int count(String sql, UUID ticketId) {
        return database.queryForObject(sql, Integer.class, ticketId);
    }

    private void cleanup(UUID ticketId) {
        database.update("DELETE FROM ticket_activities WHERE ticket_id=?", ticketId);
        database.update("DELETE FROM ticket_cancellations WHERE ticket_id=?", ticketId);
        database.update("DELETE FROM information_requests WHERE ticket_id=?", ticketId);
        database.update("DELETE FROM tickets WHERE id=?", ticketId);
    }

    private AuthenticatedIdentity citizen() {
        return new AuthenticatedIdentity("race-citizen", CITIZEN_ID, "Citizen", null, ModuleRole.CITIZEN);
    }

    private AuthenticatedIdentity agent() {
        return new AuthenticatedIdentity("race-agent", UUID.randomUUID(), "Agent", null, ModuleRole.AGENT);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        ThreadAwareClock threadAwareClock() {
            return new ThreadAwareClock(DEADLINE.minus(Duration.ofDays(3)));
        }
    }

    static final class ThreadAwareClock extends Clock {
        private final ConcurrentHashMap<Long, Instant> threadInstants = new ConcurrentHashMap<>();
        private volatile Instant defaultInstant;

        private ThreadAwareClock(Instant defaultInstant) {
            this.defaultInstant = defaultInstant;
        }

        void setDefault(Instant instant) {
            defaultInstant = instant;
        }

        void setForCurrentThread(Instant instant) {
            threadInstants.put(Thread.currentThread().threadId(), instant);
        }

        void clearCurrentThread() {
            threadInstants.remove(Thread.currentThread().threadId());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return threadInstants.getOrDefault(Thread.currentThread().threadId(), defaultInstant);
        }
    }
}
