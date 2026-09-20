package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.ResolveTicketRequest;
import com.reclamos.backend.dto.response.TicketResolutionResponse;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.TicketResolutionConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000"
})
@ActiveProfiles("dev")
class TicketResolutionNumberConcurrencyIntegrationTest {
    @Autowired private TicketResolutionService service;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private RequestTypeRepository requestTypeRepository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate database;

    @Test
    void concurrentResolutionAttemptsSerializeOnTicketAndCannotShareAnOrdinal() throws Exception {
        UUID ticketId = createResolvableTicket();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = executor.submit(() -> resolveAfterBarrier(ticketId, ready, start));
            Future<Object> second = executor.submit(() -> resolveAfterBarrier(ticketId, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Object firstResult = first.get(20, TimeUnit.SECONDS);
            Object secondResult = second.get(20, TimeUnit.SECONDS);
            long successes = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(TicketResolutionResponse.class::isInstance).count();
            long conflicts = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(TicketResolutionConflictException.class::isInstance).count();

            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(1, database.queryForObject(
                    "SELECT COUNT(*) FROM ticket_resolutions WHERE ticket_id=?", Integer.class, ticketId));
            assertEquals(1, database.queryForObject(
                    "SELECT resolution_number FROM ticket_resolutions WHERE ticket_id=?", Integer.class, ticketId));
        } finally {
            executor.shutdownNow();
            database.update("DELETE FROM ticket_activities WHERE ticket_id=?", ticketId);
            database.update("DELETE FROM ticket_resolutions WHERE ticket_id=?", ticketId);
            database.update("DELETE FROM ticket_sla WHERE ticket_id=?", ticketId);
            database.update("DELETE FROM tickets WHERE id=?", ticketId);
        }
    }

    private Object resolveAfterBarrier(UUID ticketId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return service.resolveManually(ticketId,
                    new ResolveTicketRequest(ResolutionType.ACTION_COMPLETED, "Resuelto", null),
                    new AuthenticatedIdentity("agent-" + UUID.randomUUID(), UUID.randomUUID(),
                            "AGENT", null, ModuleRole.AGENT));
        } catch (TicketResolutionConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private UUID createResolvableTicket() {
        return transactions.execute(status -> {
            RequestType requestType = requestTypeRepository.findAll().stream()
                    .filter(RequestType::isActive)
                    .findFirst()
                    .orElseThrow();
            Ticket ticket = new Ticket();
            ticket.setPublicId("CONCURRENT-" + UUID.randomUUID());
            ticket.setTrackingCodeHash("HASH-" + UUID.randomUUID());
            ticket.setAnonymous(true);
            ticket.setCitizenId(null);
            ticket.setAnonymousAccessPasswordHash("test-only-anonymous-access-password-hash");
            ticket.setRequestType(requestType);
            ticket.setTicketType(requestType.getTicketType());
            ticket.setResponsibleAreaId("M2");
            ticket.setSummary("Resolución concurrente");
            ticket.setDescription("Prueba de ordinal por ticket");
            ticket.setFormData(Map.of());
            ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
            ticket.setCurrentPriority(Priority.LOW);
            ticket.setEstimatedAffectedCount(0);
            ticket.setEscalated(false);
            ticket.setReopenCount(0);
            ticket.setStatusChangedAt(Instant.now());
            ticket.setCreatedAt(Instant.now());
            ticket.setPublic(false);
            return ticketRepository.saveAndFlush(ticket).getId();
        });
    }
}
