package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SlaPolicyRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketSlaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "ticket.sla.milestone-scan-delay=3600000")
@ActiveProfiles("dev")
class ResolutionSlaMilestoneConcurrencyIntegrationTest {
    private static final Instant NEAR = Instant.parse("2026-09-10T10:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-10T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-10T12:10:00Z");

    @Autowired private ResolutionSlaMilestoneService service;
    @Autowired private TicketRepository tickets;
    @Autowired private TicketActivityRepository activities;
    @Autowired private TicketSlaRepository slas;
    @Autowired private SlaPolicyRepository slaPolicies;
    @Autowired private RequestTypeRepository requestTypes;
    @Autowired private JdbcTemplate database;

    @Test void concurrentProcessorsCreateEachMilestoneOnlyOnce() throws Exception {
        TicketSla sla = createTicketWithSla();
        Ticket ticket = sla.getTicket();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> processAfter(start, ticket.getId(), sla.getId()));
            Future<?> second = executor.submit(() -> processAfter(start, ticket.getId(), sla.getId()));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, count(ticket.getId(), ActivityType.SLA_NEAR_DUE));
        assertEquals(1, count(ticket.getId(), ActivityType.SLA_BREACHED));
        assertEquals(1, count(ticket.getId(), ActivityType.ESCALATED));
        Ticket persisted = tickets.findById(ticket.getId()).orElseThrow();
        assertEquals(SlaStatus.BREACHED, slas.findById(sla.getId()).orElseThrow().getStatus());
        assertEquals(EscalationReasonCode.SLA_BREACHED, persisted.getEscalationReasonCode());
        cleanup(ticket.getId());
    }

    private TicketSla createTicketWithSla() {
        RequestType requestType = requestTypes.findAll().stream().filter(RequestType::isActive).findFirst().orElseThrow();
        Ticket ticket = new Ticket();
        ticket.setPublicId("SLA-RACE-" + UUID.randomUUID().toString().substring(0, 8));
        ticket.setTrackingCodeHash("sla-race-" + UUID.randomUUID());
        ticket.setCitizenId(null);
        ticket.setAnonymous(true);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId("M2");
        ticket.setSummary("Concurrencia SLA");
        ticket.setDescription("Ticket temporal para verificar idempotencia con FOR UPDATE");
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setStatusChangedAt(NEAR.minusSeconds(1));
        ticket.setResolutionDueAt(DUE);
        ticket = tickets.saveAndFlush(ticket);

        TicketSla sla = new TicketSla();
        sla.setTicket(ticket);
        sla.setSlaType(SlaType.RESOLUTION);
        sla.setCycleNumber(1);
        sla.setPolicy(slaPolicies.findByPriorityAndSlaType(Priority.HIGH, SlaType.RESOLUTION).orElseThrow());
        sla.setStartedAt(NEAR.minusSeconds(3600));
        sla.setNearDueAt(NEAR);
        sla.setDueAt(DUE);
        sla.setStatus(SlaStatus.RUNNING);
        return slas.saveAndFlush(sla);
    }

    private void processAfter(CountDownLatch start, UUID ticketId, Long slaId) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            service.processCandidate(ticketId, slaId, NOW);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private int count(UUID ticketId, ActivityType type) {
        return database.queryForObject(
                "SELECT COUNT(*) FROM ticket_activities WHERE ticket_id=? AND action_type=?",
                Integer.class, ticketId, type.name());
    }

    private void cleanup(UUID ticketId) {
        database.update("DELETE FROM ticket_activities WHERE ticket_id=?", ticketId);
        database.update("DELETE FROM ticket_sla WHERE ticket_id=?", ticketId);
        database.update("DELETE FROM tickets WHERE id=?", ticketId);
    }
}
