package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/** Service transactions commit before assertions; fixtures are isolated from the seeded catalog. */
@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"
})
@ActiveProfiles("dev")
class TicketAffectedCountPostgresIntegrationTest {
    @Autowired private TicketService service;
    @Autowired private JdbcTemplate db;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;

    private final List<UUID> ticketIds = new ArrayList<>();
    private final List<Long> typeIds = new ArrayList<>();
    private final UUID neighborhoodId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final String marker = "H07-" + UUID.randomUUID();
    private Long selectedType;

    @BeforeEach
    void setUp() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        db.update("INSERT INTO neighborhood(id,name,population) VALUES (?,?,13912)", neighborhoodId, marker);
        db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?,'H07','Agent','AGENT',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", agentId);
        db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?,'H07','Owner','CITIZEN',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ownerId);
        selectedType = type("0.2500");
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : ticketIds) {
            for (String table : List.of("notification_logs", "outbox_events", "ticket_activities", "ticket_sla",
                    "ticket_locations")) {
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            }
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        for (Long id : typeIds) db.update("DELETE FROM request_types WHERE id=?", id);
        db.update("DELETE FROM neighborhood WHERE id=?", neighborhoodId);
        db.update("DELETE FROM module_users WHERE citizen_id=?", agentId);
        db.update("DELETE FROM module_users WHERE citizen_id=?", ownerId);
    }

    static Stream<Arguments> calculations() {
        // Preserve the existing HALF_UP policy, including its .5 boundary.
        return Stream.of(
                new Object[]{13912, "0.2500", 3478},
                new Object[]{13912, "0.0000", 0},
                new Object[]{0, "1.0000", 0},
                new Object[]{13912, "1.0000", 13912},
                new Object[]{10000, "0.0001", 1},
                new Object[]{1, "0.4999", 0},
                new Object[]{1, "0.5000", 1},
                new Object[]{3, "0.5000", 2},
                new Object[]{Integer.MAX_VALUE, "1.0000", Integer.MAX_VALUE}
        ).flatMap(values -> Stream.of(false, true).map(anonymous ->
                Arguments.of(values[0], values[1], values[2], anonymous)));
    }

    @ParameterizedTest
    @MethodSource("calculations")
    void creationPersistsServerSideEstimateForSelectedTypeAndNeighborhood(
            int population, String factor, int expected, boolean anonymous) {
        db.update("UPDATE neighborhood SET population=? WHERE id=?", population, neighborhoodId);
        db.update("UPDATE request_types SET affected_population_factor=? WHERE id=?", new BigDecimal(factor), selectedType);

        UUID id = create(anonymous, location(neighborhoodId));

        assertThat(count(id)).isEqualTo(expected);
        assertThat(db.queryForObject("SELECT neighborhood_id FROM ticket_locations WHERE ticket_id=?", UUID.class, id))
                .isEqualTo(neighborhoodId);
        assertThat(ticket(id)).containsEntry("request_type_id", selectedType).containsEntry("is_anonymous", anonymous)
                .containsEntry("current_priority", "LOW").containsEntry("current_status", "REGISTERED")
                .containsEntry("is_escalated", false);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? AND action_type='TICKET_CREATED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(sla(id)).hasSize(2).allSatisfy(cycle -> {
            assertThat(cycle).containsEntry("cycle_number", 1).containsEntry("status", "RUNNING");
            assertThat(((java.sql.Timestamp) cycle.get("due_at")).toInstant())
                    .isAfter(((java.sql.Timestamp) cycle.get("started_at")).toInstant());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optionalLocationOrNeighborhoodKeepsExistingZeroFallback(boolean anonymous) {
        Long target = type("1.0000");
        for (CreateTicketRequest.LocationData data : new CreateTicketRequest.LocationData[]{null, location(null)}) {
            UUID id = create(anonymous, data);
            assertThat(count(id)).isZero();
            service.startReview(id, agent());
            assertThat(service.correctClassification(id, target, agent()).getEstimatedAffectedCount()).isZero();
            assertThat(count(id)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownNeighborhoodIsRejectedWithoutPersistingTicketOrDependentRows(boolean anonymous) {
        var before = tableCounts();
        assertThatThrownBy(() -> create(anonymous, location(UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(tableCounts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorizedInitialReclassificationUsesConfirmedTypeAndPreservesOtherTicketsAndSlaOrigin(boolean anonymous) {
        UUID id = create(anonymous, location(neighborhoodId));
        UUID other = create(!anonymous, location(neighborhoodId));
        service.startReview(id, agent());
        var originalCycles = sla(id);
        var originalOther = ticket(other);
        Long confirmedType = type("0.5000");

        var response = service.correctClassification(id, confirmedType,
                anonymous ? agent() : identity(UUID.randomUUID(), ModuleRole.ADMIN));

        assertThat(count(id)).isEqualTo(6956);
        assertThat(response.getEstimatedAffectedCount()).isEqualTo(6956);
        assertThat(ticket(id)).containsEntry("request_type_id", confirmedType).containsEntry("current_priority", "LOW")
                .containsEntry("current_status", "IN_REVIEW");
        var currentCycles = sla(id);
        assertThat(currentCycles).extracting(cycle -> cycle.get("id"))
                .containsExactlyElementsOf(originalCycles.stream().map(cycle -> cycle.get("id")).toList());
        assertThat(currentCycles.getFirst()).isEqualTo(originalCycles.getFirst());
        assertThat(currentCycles.getLast()).containsEntry("cycle_number", 1)
                .containsEntry("started_at", ticket(id).get("created_at"));
        assertThat(ticket(other)).isEqualTo(originalOther);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? AND action_type='REQUEST_TYPE_CHANGED'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void readsReviewAndSameTypeConfirmationDoNotRetroactivelyRecalculate() {
        UUID id = create(false, location(neighborhoodId));
        db.update("UPDATE neighborhood SET population=20000 WHERE id=?", neighborhoodId);
        db.update("UPDATE request_types SET affected_population_factor=1 WHERE id=?", selectedType);

        assertThat(service.getStaffDetail(id, agent()).getEstimatedAffectedCount()).isEqualTo(3478);
        service.startReview(id, agent());
        var before = snapshot(id);
        assertThat(service.correctClassification(id, selectedType, agent()).getEstimatedAffectedCount()).isEqualTo(3478);
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void rejectedReclassificationPreservesEstimateAndAllTransactionalState() {
        UUID id = create(false, location(neighborhoodId));
        Long target = type("1.0000");
        var beforeReview = snapshot(id);
        assertThatThrownBy(() -> service.correctClassification(id, target, agent()))
                .isInstanceOf(TicketStateConflictException.class);
        assertThat(snapshot(id)).isEqualTo(beforeReview);
        service.startReview(id, agent());
        var before = snapshot(id);
        assertThatThrownBy(() -> service.correctClassification(id, target, identity(ownerId, ModuleRole.ADMIN)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThat(snapshot(id)).isEqualTo(before);
        service.routeToArea(id, agent());
        var finalized = snapshot(id);
        assertThatThrownBy(() -> service.correctClassification(id, target, agent()))
                .isInstanceOf(TicketStateConflictException.class);
        assertThat(snapshot(id)).isEqualTo(finalized);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateFailureRollsBackCreationAndAllDependentPersistence(boolean anonymous) {
        var before = tableCounts();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID id = create(anonymous, location(neighborhoodId));
            entityManager.flush();
            assertThat(count(id)).isEqualTo(3478);
            assertThat(sla(id)).hasSize(2);
            throw new IllegalStateException("H07 simulated late failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("H07 simulated late failure");
        assertThat(tableCounts()).isEqualTo(before);
    }

    @Test
    void lateFailureRollsBackClassificationEstimateHistoryAndSlaTogether() {
        UUID id = create(false, location(neighborhoodId));
        service.startReview(id, agent());
        Long target = type("1.0000");
        var before = snapshot(id);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.correctClassification(id, target, agent());
            entityManager.flush();
            assertThat(count(id)).isEqualTo(13912);
            throw new IllegalStateException("H07 simulated late failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("H07 simulated late failure");
        assertThat(snapshot(id)).isEqualTo(before);
    }

    private Long type(String factor) {
        String code = marker + "-" + typeIds.size();
        Long id = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,"
                + "responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT ?,id,?,?,'REQUEST','M6','LOW','LOW',?,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id",
                Long.class, code, code, marker, new BigDecimal(factor));
        typeIds.add(id);
        return id;
    }

    private UUID create(boolean anonymous, CreateTicketRequest.LocationData location) {
        UUID id = service.create(new CreateTicketRequest(selectedType, marker, "H07 affected count", Map.of(), location),
                anonymous ? null : identity(ownerId, ModuleRole.CITIZEN), null).ticketId();
        ticketIds.add(id);
        return id;
    }

    private CreateTicketRequest.LocationData location(UUID neighborhood) {
        return new CreateTicketRequest.LocationData("Lima 123", null, null, neighborhood, null, null, null);
    }

    private AuthenticatedIdentity agent() { return identity(agentId, ModuleRole.AGENT); }

    private AuthenticatedIdentity identity(UUID citizenId, ModuleRole role) {
        return new AuthenticatedIdentity("h07", citizenId, "H07", "M2", role);
    }

    private int count(UUID id) {
        return db.queryForObject("SELECT estimated_affected_count FROM tickets WHERE id=?", Integer.class, id);
    }

    private Map<String, Object> ticket(UUID id) {
        return db.queryForMap("SELECT * FROM tickets WHERE id=?", id);
    }

    private List<Map<String, Object>> sla(UUID id) {
        return db.queryForList("SELECT id,sla_type,cycle_number,status,started_at,near_due_at,due_at "
                + "FROM ticket_sla WHERE ticket_id=? ORDER BY sla_type,cycle_number", id);
    }

    private List<Object> snapshot(UUID id) {
        return List.of(ticket(id), sla(id),
                db.queryForList("SELECT * FROM ticket_locations WHERE ticket_id=?", id),
                db.queryForList("SELECT * FROM ticket_activities WHERE ticket_id=? ORDER BY sequence", id),
                db.queryForList("SELECT * FROM outbox_events WHERE ticket_id=? ORDER BY created_at,id", id));
    }

    private List<Long> tableCounts() {
        return Stream.of("tickets", "ticket_locations", "ticket_sla", "ticket_activities", "outbox_events", "notification_logs")
                .map(table -> db.queryForObject("SELECT count(*) FROM " + table, Long.class)).toList();
    }
}
