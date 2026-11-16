package com.reclamos.backend.service;

import com.reclamos.backend.entity.NotificationLog;
import com.reclamos.backend.notification.NotificationDelivery;
import com.reclamos.backend.notification.NotificationSender;
import com.reclamos.backend.repository.NotificationLogRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.entity.NotificationType;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000",
        // These tests drive the worker explicitly to control transaction boundaries.
        // H25 separately exercises the actual periodic job under load.
        "notification.pending-scan-delay=3600000",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.flyway.schemas=notification_worker_test",
        "spring.datasource.hikari.schema=notification_worker_test",
        "spring.jpa.properties.hibernate.default_schema=notification_worker_test"
})
@ActiveProfiles("dev")
@Import(NotificationWorkerPostgresIntegrationTest.SenderConfiguration.class)
class NotificationWorkerPostgresIntegrationTest {
    @Autowired private NotificationProcessor processor;
    @Autowired private NotificationDeliveryJob job;
    @Autowired private NotificationService notifications;
    @Autowired private TicketRepository tickets;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate database;
    @Autowired private ControlledSender sender;
    @Autowired private HikariDataSource dataSource;
    @MockitoSpyBean private NotificationLogRepository notificationLogs;
    private UUID ticketId;

    @BeforeEach
    void setUp() {
        assertTrue(database.queryForObject("SELECT version()", String.class).startsWith("PostgreSQL"));
        assertEquals(10, dataSource.getMaximumPoolSize());
        sender.reset();
        ticketId = UUID.randomUUID();
        assertEquals(1, database.update("INSERT INTO tickets "
                + "(id,public_id,tracking_code_hash,is_anonymous,anonymous_access_password_hash,anonymous_contact_channel,"
                + "anonymous_contact_value,request_type_id,ticket_type,responsible_area_id,summary,description,form_data,"
                + "current_status,current_priority,is_escalated,status_changed_at,created_at,updated_at) "
                + "SELECT ?, ?, ?, true, 'test-only-hash', 'EMAIL', 'test@example.invalid', id, ticket_type, 'M2', "
                + "'Notification worker', 'PostgreSQL regression', '{}'::jsonb, 'REGISTERED', 'LOW', false, "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM request_types WHERE code='INFORMAR_UN_BACHE'",
                ticketId, "NW-" + ticketId, "hash-" + ticketId));
    }

    @AfterEach
    void cleanUp() {
        sender.release.countDown();
        reset(notificationLogs);
        database.update("DELETE FROM notification_logs WHERE ticket_id=?", ticketId);
        database.update("DELETE FROM tickets WHERE id=?", ticketId);
    }

    @Test
    void uncommittedNotificationIsNotVisibleAndRollbackNeverDelivers() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transactions.executeWithoutResult(status -> {
                notifications.queueCitizen(tickets.findById(ticketId).orElseThrow(), NotificationType.CLOSED);
                try {
                    assertFalse(executor.submit(processor::processNextPending).get(5, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                assertTrue(sender.deliveries.isEmpty());
                status.setRollbackOnly();
            });
        }
        job.deliverPending();
        assertEquals(0, count("true"));
        assertTrue(sender.deliveries.isEmpty());
    }

    @Test
    void committedBusinessCallReturnsAndReleasesItsConnectionWhileSenderIsBlocked() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            // The real publication path commits without invoking the sender on its thread.
            long id = executor.submit(() -> transactions.execute(status -> notifications.queueCitizen(
                    tickets.findById(ticketId).orElseThrow(), NotificationType.CLOSED).orElseThrow().getId()))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, count("status='PENDING' AND attempt_count=0"));
            sender.blockedId = id;
            var delivery = executor.submit(processor::processNextPending);
            try {
                assertTrue(sender.entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, dataSource.getHikariPoolMXBean().getActiveConnections(),
                        "Sólo la transacción propia del worker conserva una conexión");
                assertFalse(delivery.isDone());
            } finally {
                sender.release.countDown();
            }
            assertTrue(delivery.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count("status='SENT' AND attempt_count=1 AND sent_at IS NOT NULL"));
        assertEquals(1, sender.deliveries.size());
    }

    @Test
    void committedPendingWithoutAnEventIsRecoveredByTheWorker() {
        insertPending(); // Models a restart or a missed in-memory signal.
        job.deliverPending();
        job.deliverPending();
        assertEquals(1, count("status='SENT' AND attempt_count=1"));
        assertEquals(1, sender.deliveries.size());
    }

    @Test
    void concurrentWorkersSkipTheClaimedRowAndNeverDeliverItTwice() throws Exception {
        long firstId = insertPending();
        long secondId = insertPending();
        sender.blockedId = firstId;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(processor::processNextPending);
            try {
                assertTrue(sender.entered.await(5, TimeUnit.SECONDS));
                assertTrue(executor.submit(processor::processNextPending).get(5, TimeUnit.SECONDS),
                        "El segundo worker debe omitir la fila bloqueada y procesar la siguiente");
                assertEquals("SENT", database.queryForObject("SELECT status FROM notification_logs WHERE id=?", String.class, secondId));
                assertFalse(executor.submit(processor::processNextPending).get(5, TimeUnit.SECONDS));
            } finally {
                sender.release.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, count("status='SENT' AND attempt_count=1"));
        assertEquals(2, sender.deliveries.size());
        assertEquals(2, sender.deliveries.stream().map(NotificationDelivery::getNotificationId).distinct().count());
    }

    @Test
    void backlogExceedingOneScanRemainsDurableAndIsFullyDrainedWithoutTaskRejections() {
        for (int i = 0; i < 125; i++) insertPending();
        job.deliverPending();
        assertEquals(50, count("status='SENT'"));
        assertEquals(75, count("status='PENDING' AND attempt_count=0"));
        job.deliverPending();
        assertEquals(100, count("status='SENT'"));
        job.deliverPending();
        assertEquals(125, count("status='SENT' AND attempt_count=1"));
        assertEquals(125, sender.deliveries.stream().map(NotificationDelivery::getNotificationId).distinct().count());
    }

    @Test
    void persistenceFailureRollsBackTheClaimAndPendingIsRecoveredOnALaterScan() {
        insertPending();
        doThrow(new DataAccessResourceFailureException("test-only persistence failure"))
                .when(notificationLogs).save(any(NotificationLog.class));

        job.deliverPending();
        assertEquals(1, count("status='PENDING' AND attempt_count=0 AND sent_at IS NULL"));
        reset(notificationLogs);
        job.deliverPending();

        assertEquals(1, count("status='SENT' AND attempt_count=1"));
        // The existing send-before-commit window is at-least-once, not exactly-once.
        assertEquals(2, sender.deliveries.size());
    }

    @Test
    void senderFailureStaysFailedAndDoesNotPreventTheNextNotification() {
        sender.failedId = insertPending();
        insertPending();
        job.deliverPending();
        job.deliverPending();
        assertEquals(1, count("status='FAILED' AND attempt_count=1 AND failed_at IS NOT NULL AND sent_at IS NULL "
                + "AND failure_reason='Notification delivery failed (IllegalStateException)'"));
        assertEquals(1, count("status='SENT' AND attempt_count=1"));
        assertEquals(2, sender.deliveries.size());
    }

    private long insertPending() {
        return database.queryForObject("INSERT INTO notification_logs (ticket_id,type,channel,status,attempt_count) "
                + "VALUES (?,'CLOSED','EMAIL','PENDING',0) RETURNING id", Long.class, ticketId);
    }

    private int count(String condition) {
        return database.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=? AND (" + condition + ")",
                Integer.class, ticketId);
    }

    @TestConfiguration
    static class SenderConfiguration {
        @Bean @Primary ControlledSender controlledSender() { return new ControlledSender(); }
    }

    static class ControlledSender implements NotificationSender {
        final List<NotificationDelivery> deliveries = new CopyOnWriteArrayList<>();
        volatile long blockedId;
        volatile long failedId;
        CountDownLatch entered;
        CountDownLatch release;

        void reset() {
            deliveries.clear();
            blockedId = -1;
            failedId = -1;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public void send(NotificationDelivery notification) {
            deliveries.add(notification);
            if (notification.getNotificationId() == blockedId) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test sender timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            if (notification.getNotificationId() == failedId) throw new IllegalStateException("sensitive test data");
        }
    }
}
