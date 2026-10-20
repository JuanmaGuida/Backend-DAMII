package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.LinkDuplicateRequest;
import com.reclamos.backend.dto.request.TicketMessageRequest;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Requires real PostgreSQL, the normal Flyway migrations and JPA schema validation. */
@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000",
        "spring.datasource.hikari.maximum-pool-size=10",
        "notification.pending-scan-delay=50"
})
@ActiveProfiles("dev")
class TicketIntegrityPostgresIntegrationTest {
    @Autowired private TicketMessageService messages;
    @Autowired private DuplicateTicketService duplicates;
    @Autowired private TicketService tickets;
    @Autowired private AnonymousTicketAccessService anonymousAccess;
    @Autowired private AnonymousTicketCredentialService credentials;
    @Autowired private TrackingCodeService trackingCodes;
    @Autowired private JdbcTemplate database;
    @Autowired private HikariDataSource dataSource;

    private final List<UUID> ticketIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private AuthenticatedIdentity agent;
    private AuthenticatedIdentity owner;
    private String anonymousPasswordHash;

    @BeforeEach
    void setUp() {
        assertTrue(database.queryForObject("SELECT version()", String.class).startsWith("PostgreSQL"));
        assertEquals(10, dataSource.getMaximumPoolSize());
        agent = identity(ModuleRole.AGENT);
        owner = identity(ModuleRole.CITIZEN);
        anonymousPasswordHash = credentials.prepare("concurrency-test-password").passwordHash();
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : ticketIds) {
            for (String table : List.of("notification_logs", "outbox_events", "ticket_messages",
                    "ticket_activities", "ticket_sla")) {
                database.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            }
        }
        // Remove all links before deleting principals, independent of fixture insertion order.
        for (UUID id : ticketIds) database.update("UPDATE tickets SET main_ticket_id=NULL WHERE id=?", id);
        for (UUID id : ticketIds) database.update("DELETE FROM tickets WHERE id=?", id);
        for (UUID id : userIds) database.update("DELETE FROM module_users WHERE citizen_id=?", id);
    }

    @Test
    void twentyFourConcurrentIdentifiedOwnerMessagesAllCommitWithMatchingOrderedActivities() throws Exception {
        UUID id = createTicket(TicketStatus.REGISTERED, false);
        assignAgent(id);
        concurrently(messageTasks(id, false, 24));
        assertMessagesAndSequence(id, 24, 25, owner.citizenId().toString());
        assertNotificationsDelivered(id, 24);
    }

    @Test
    void twentyFourConcurrentAccreditedAnonymousMessagesAllCommitWithMatchingOrderedActivities() throws Exception {
        UUID id = createTicket(TicketStatus.REGISTERED, true);
        assignAgent(id);
        concurrently(messageTasks(id, true, 24));
        assertMessagesAndSequence(id, 24, 25, null);
        assertNotificationsDelivered(id, 24);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void messagesAndLifecycleTransitionShareTheTicketLock(boolean anonymous) throws Exception {
        UUID id = createTicket(TicketStatus.REGISTERED, anonymous);
        assignAgent(id);
        List<Callable<Object>> operations = messageTasks(id, anonymous, 24);
        operations.add(() -> tickets.startReview(id, agent));
        concurrently(operations);

        assertMessagesAndSequence(id, 24, 26, anonymous ? null : owner.citizenId().toString());
        assertEquals("IN_REVIEW", database.queryForObject(
                "SELECT current_status FROM tickets WHERE id=?", String.class, id));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM ticket_activities "
                + "WHERE ticket_id=? AND action_type='REVIEW_STARTED'", Integer.class, id));
        assertNotificationsDelivered(id, 24);
    }

    @Test
    void invalidMessagePermissionsStillLeaveNoWrites() {
        UUID id = createTicket(TicketStatus.REGISTERED, false);
        var before = snapshot(id);
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> messages.create(id, message("internal", MessageVisibility.INTERNAL), owner));
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> messages.createAnonymous(id, message("anonymous", MessageVisibility.PUBLIC)));
        assertEquals(before, snapshot(id));
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"CANCELLED", "CLOSED", "RESOLVED"})
    void terminalSourceRejectionPreservesAllPersistedState(TicketStatus status) {
        UUID source = createTicket(status, false);
        UUID main = createTicket(TicketStatus.IN_PROGRESS, true);
        addSla(source);
        var before = snapshot(source);
        var mainBefore = snapshot(main);

        assertThrows(TicketStateConflictException.class, () -> link(source, main));

        assertEquals(before, snapshot(source));
        assertEquals(mainBefore, snapshot(main));
    }

    @Test
    void validLinkIsIdempotentAndLaterChainRejectionPreservesLinksHistorySlaAndEvents() {
        UUID a = createTicket(TicketStatus.IN_REVIEW, true);
        UUID b = createTicket(TicketStatus.REGISTERED, false);
        UUID c = createTicket(TicketStatus.IN_PROGRESS, true);
        addSla(b);
        link(b, a);
        var bBefore = snapshot(b);
        var aBefore = snapshot(a);
        var cBefore = snapshot(c);

        link(b, a);
        assertThrows(TicketStateConflictException.class, () -> link(a, c));
        assertThrows(TicketStateConflictException.class, () -> link(c, b));
        assertThrows(TicketStateConflictException.class, () -> link(a, b));
        assertThrows(TicketStateConflictException.class, () -> link(a, a));

        assertEquals(bBefore, snapshot(b));
        assertEquals(aBefore, snapshot(a));
        assertEquals(cBefore, snapshot(c));
        assertEquals(a, database.queryForObject("SELECT main_ticket_id FROM tickets WHERE id=?", UUID.class, b));
        assertEquals("STOPPED", database.queryForObject("SELECT status FROM ticket_sla WHERE ticket_id=?", String.class, b));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=?", Integer.class, b));
    }

    @Test
    void evenAnInactiveChildPreventsTurningItsPrincipalIntoADuplicate() {
        UUID a = createTicket(TicketStatus.IN_PROGRESS, true);
        UUID b = createTicket(TicketStatus.REGISTERED, true);
        UUID c = createTicket(TicketStatus.IN_PROGRESS, true);
        link(b, a);
        database.update("UPDATE tickets SET current_status='CLOSED' WHERE id=?", b);
        var before = snapshot(a);
        assertThrows(TicketStateConflictException.class, () -> link(a, c));
        assertEquals(before, snapshot(a));
    }

    @Test
    void incomingLinkAndPrincipalConversionCannotConcurrentlyCreateAChain() throws Exception {
        // Both possible winners are valid, but exactly one link must be rejected.
        for (int attempt = 0; attempt < 6; attempt++) {
            UUID a = createTicket(TicketStatus.IN_REVIEW, true);
            UUID b = createTicket(TicketStatus.REGISTERED, true);
            UUID c = createTicket(TicketStatus.IN_PROGRESS, true);
            assertOneLinkWins(List.of(() -> link(b, a), () -> link(a, c)));
            assertDirectPrincipals();
        }
    }

    @Test
    void opposingLinksCannotConcurrentlyCreateACycle() throws Exception {
        UUID a = createTicket(TicketStatus.IN_REVIEW, true);
        UUID b = createTicket(TicketStatus.REGISTERED, true);
        assertOneLinkWins(List.of(() -> link(a, b), () -> link(b, a)));
        assertDirectPrincipals();
    }

    private void assertOneLinkWins(List<Callable<Object>> links) throws Exception {
        List<Callable<Object>> attempts = links.stream().<Callable<Object>>map(operation -> () -> {
            try {
                return operation.call();
            } catch (TicketStateConflictException conflict) {
                return conflict;
            }
        }).toList();
        List<Object> results = concurrently(attempts);
        assertEquals(1, results.stream().filter(TicketStateConflictException.class::isInstance).count());
    }

    private void assertDirectPrincipals() {
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tickets child JOIN tickets main "
                + "ON child.main_ticket_id=main.id WHERE main.main_ticket_id IS NOT NULL "
                + "OR main.current_status='DUPLICATE'", Integer.class));
    }

    private Object link(UUID source, UUID main) {
        return duplicates.link(source, new LinkDuplicateRequest(main), agent);
    }

    private List<Callable<Object>> messageTasks(UUID id, boolean anonymous, int count) {
        String trackingCode = trackingCodes.generate();
        if (anonymous) database.update("UPDATE tickets SET tracking_code_hash=? WHERE id=?", trackingCodes.hash(trackingCode), id);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = "Concurrent message " + i;
            tasks.add(() -> {
                TicketMessageRequest request = message(text, MessageVisibility.PUBLIC);
                if (anonymous) {
                    UUID authenticated = anonymousAccess.authenticate(trackingCode, "concurrency-test-password").ticketId();
                    return messages.createAnonymous(authenticated, request);
                }
                return messages.create(id, request, owner);
            });
        }
        return tasks;
    }

    private TicketMessageRequest message(String text, MessageVisibility visibility) {
        TicketMessageRequest request = new TicketMessageRequest();
        request.setText(text);
        request.setVisibility(visibility);
        return request;
    }

    private List<Object> concurrently(List<Callable<Object>> operations) throws Exception {
        var executor = Executors.newFixedThreadPool(operations.size());
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> operation : operations) futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return operation.call();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) results.add(future.get(45, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void assertMessagesAndSequence(UUID id, int messageCount, int activityCount, String actorId) {
        assertEquals(messageCount, database.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id=?", Integer.class, id));
        assertEquals(IntStream.rangeClosed(1, activityCount).boxed().toList(), database.queryForList(
                "SELECT sequence FROM ticket_activities WHERE ticket_id=? ORDER BY sequence", Integer.class, id));
        assertEquals(messageCount, database.queryForObject("SELECT count(*) FROM ticket_messages m "
                + "JOIN ticket_activities a ON a.ticket_id=m.ticket_id AND a.message=m.text "
                + "AND a.actor_type=m.author_type AND a.actor_id IS NOT DISTINCT FROM m.author_id "
                + "AND a.source_module_id=m.source_module_id AND a.action_type='PUBLIC_MESSAGE_SENT' "
                + "WHERE m.ticket_id=? AND m.visibility='PUBLIC' AND m.author_id IS NOT DISTINCT FROM CAST(? AS varchar)",
                Integer.class, id, actorId));
    }

    private void assignAgent(UUID id) {
        database.update("UPDATE tickets SET assigned_agent_id=(SELECT id FROM module_users WHERE citizen_id=?) WHERE id=?",
                agent.citizenId(), id);
    }

    private void assertNotificationsDelivered(UUID id, int expected) {
        assertEquals(expected, database.queryForObject("SELECT count(*) FROM notification_logs WHERE ticket_id=?",
                Integer.class, id));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(expected, database.queryForObject("SELECT count(*) FROM notification_logs "
                        + "WHERE ticket_id=? AND citizen_id=? AND type='PUBLIC_MESSAGE_RECEIVED' "
                        + "AND status='SENT' AND attempt_count=1 AND sent_at IS NOT NULL "
                        + "AND failed_at IS NULL AND failure_reason IS NULL", Integer.class, id, agent.citizenId())));
    }

    private List<List<Map<String, Object>>> snapshot(UUID id) {
        List<List<Map<String, Object>>> result = new ArrayList<>();
        result.add(database.queryForList("SELECT * FROM tickets WHERE id=?", id));
        for (String table : List.of("ticket_activities", "ticket_sla", "outbox_events", "ticket_messages", "notification_logs")) {
            result.add(database.queryForList("SELECT * FROM " + table + " WHERE ticket_id=? ORDER BY id", id));
        }
        return result;
    }

    private AuthenticatedIdentity identity(ModuleRole role) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO module_users (citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?, 'H06H25', 'Test', ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, role.name());
        userIds.add(id);
        return new AuthenticatedIdentity("test-" + id, id, "H06H25", "M2", role);
    }

    private UUID createTicket(TicketStatus status, boolean anonymous) {
        UUID id = UUID.randomUUID();
        assertEquals(1, database.update("INSERT INTO tickets "
                + "(id,public_id,tracking_code_hash,citizen_id,is_anonymous,anonymous_access_password_hash,request_type_id,ticket_type,"
                + "responsible_area_id,summary,description,form_data,current_status,current_priority,is_escalated,status_changed_at,created_at,updated_at) "
                + "SELECT ?, ?, ?, ?, ?, ?, id, ticket_type, 'M2', 'H06H25', 'Concurrency regression', '{}'::jsonb, ?, 'LOW', false, "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM request_types WHERE code='INFORMAR_UN_BACHE'",
                id, "H0625-" + id, "hash-" + id, anonymous ? null : owner.citizenId(), anonymous,
                anonymous ? anonymousPasswordHash : null, status.name()));
        ticketIds.add(id);
        database.update("INSERT INTO ticket_activities (ticket_id,sequence,action_type,actor_type,source_module_id,occurred_at,created_at) "
                + "VALUES (?,1,'TICKET_CREATED','CITIZEN','M2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id);
        return id;
    }

    private void addSla(UUID id) {
        assertEquals(1, database.update("INSERT INTO ticket_sla "
                + "(ticket_id,sla_type,cycle_number,policy_id,started_at,near_due_at,due_at,status,total_paused_seconds) "
                + "SELECT ?, 'RESOLUTION', 1, id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '10 days', "
                + "CURRENT_TIMESTAMP + interval '11 days', 'RUNNING', 0 FROM sla_policies WHERE priority='LOW' AND sla_type='RESOLUTION'", id));
    }
}
