package com.reclamos.backend.repository;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.ModuleRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
class TicketLabelPostgresIntegrationTest {

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private RequestTypeRepository requestTypes;

    @Autowired
    private LabelRepository labels;

    @Autowired
    private TicketLabelRepository assignments;

    @Autowired
    private ModuleUserRepository moduleUsers;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> ticketIds = new ArrayList<>();
    private final List<UUID> labelIds = new ArrayList<>();
    private final List<Long> moduleUserIds = new ArrayList<>();

    @AfterEach
    void clean() {
        ticketIds.forEach(id ->
                jdbc.update("DELETE FROM ticket_labels WHERE ticket_id = ?", id));

        ticketIds.forEach(id ->
                jdbc.update("DELETE FROM tickets WHERE id = ?", id));

        labelIds.forEach(id ->
                jdbc.update("DELETE FROM labels WHERE id = ?", id));

        moduleUserIds.forEach(id ->
                jdbc.update("DELETE FROM module_users WHERE id = ?", id));
    }

    @Test
    void foreignKeysRejectMissingTicketAndMissingLabel() {
        Label label = label("FK");

        assertThatThrownBy(() ->
                jdbc.update("""
                                INSERT INTO ticket_labels
                                    (ticket_id, label_id, source, created_by, created_at)
                                VALUES (?, ?, 'MANUAL', ?, now())
                                """,
                        UUID.randomUUID(),
                        label.getId(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        Ticket ticket = ticket("fk");

        assertThatThrownBy(() ->
                jdbc.update("""
                                INSERT INTO ticket_labels
                                    (ticket_id, label_id, source, created_by, created_at)
                                VALUES (?, ?, 'MANUAL', ?, now())
                                """,
                        ticket.getId(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void duplicateAssignmentIsOneManualRowWithActorAndTimestamp() {
        Ticket ticket = ticket("unique");
        Label label = label("UNIQUE");
        UUID actor = UUID.randomUUID();

        Instant createdAt = Instant.parse("2026-09-20T12:00:00Z");

        assertThat(
                assignments.insertManualIfAbsent(
                        ticket.getId(),
                        label.getId(),
                        actor,
                        createdAt))
                .isOne();

        assertThat(
                assignments.insertManualIfAbsent(
                        ticket.getId(),
                        label.getId(),
                        actor,
                        createdAt))
                .isZero();

        Map<String, Object> row = jdbc.queryForMap("""
                        SELECT source, created_by, created_at
                        FROM ticket_labels
                        WHERE ticket_id = ?
                          AND label_id = ?
                        """,
                ticket.getId(),
                label.getId());

        assertThat(row.get("source")).isEqualTo("MANUAL");
        assertThat(row.get("created_by")).isEqualTo(actor);
        assertThat(row.get("created_at")).isNotNull();

        assertThat(
                jdbc.queryForObject("""
                                SELECT count(*)
                                FROM ticket_labels
                                WHERE ticket_id = ?
                                  AND label_id = ?
                                """,
                        Integer.class,
                        ticket.getId(),
                        label.getId()))
                .isOne();
    }

    @Test
    @Transactional
    void realExistsFilterUsesAnyWithoutDuplicatesAndKeepsPaginationCount() {
        Label a = label("ANY-A");
        Label b = label("ANY-B");

        Ticket both = ticket("both");
        Ticket onlyB = ticket("only-b");
        Ticket neither = ticket("neither");

        UUID actor = UUID.randomUUID();
        Instant now = Instant.now();

        assignments.insertManualIfAbsent(
                both.getId(),
                a.getId(),
                actor,
                now);

        assignments.insertManualIfAbsent(
                both.getId(),
                b.getId(),
                actor,
                now);

        assignments.insertManualIfAbsent(
                onlyB.getId(),
                b.getId(),
                actor,
                now);

        var one = tickets.findAll(
                TicketSpecifications.build(filter(Set.of(a.getId()))),
                PageRequest.of(0, 10));

        assertThat(one.getContent())
                .extracting(Ticket::getId)
                .containsExactly(both.getId());

        var anyFirstPage = tickets.findAll(
                TicketSpecifications.build(
                        filter(Set.of(a.getId(), b.getId()))),
                PageRequest.of(0, 1));

        assertThat(anyFirstPage.getTotalElements()).isEqualTo(2);
        assertThat(anyFirstPage.getTotalPages()).isEqualTo(2);
        assertThat(anyFirstPage.getContent()).hasSize(1);

        var allAny = tickets.findAll(
                TicketSpecifications.build(
                        filter(Set.of(a.getId(), b.getId()))),
                PageRequest.of(0, 10));

        assertThat(allAny.getContent())
                .extracting(Ticket::getId)
                .containsExactlyInAnyOrder(
                        both.getId(),
                        onlyB.getId());

        assertThat(allAny.getContent())
                .extracting(Ticket::getId)
                .doesNotHaveDuplicates()
                .doesNotContain(neither.getId());

        var missing = tickets.findAll(
                TicketSpecifications.build(filter(Set.of(UUID.randomUUID()))),
                PageRequest.of(0, 10));
        assertThat(missing).isEmpty();

        onlyB.setCurrentStatus(TicketStatus.IN_PROGRESS);
        onlyB.setCurrentPriority(Priority.HIGH);
        tickets.saveAndFlush(onlyB);

        TicketFilter combined = new TicketFilter(
                null, Priority.HIGH, null, null, TicketStatus.IN_PROGRESS,
                Set.of(a.getId(), b.getId()));
        assertThat(tickets.findAll(TicketSpecifications.build(combined), PageRequest.of(0, 10)).getContent())
                .extracting(Ticket::getId)
                .containsExactly(onlyB.getId());

        var sorted = tickets.findAll(
                TicketSpecifications.build(filter(Set.of(a.getId(), b.getId()))),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "summary")));
        assertThat(sorted.getContent())
                .extracting(Ticket::getSummary)
                .isSorted();

        long withoutFilter =
                tickets.count(
                        TicketSpecifications.build(filter(null)));

        assertThat(withoutFilter).isGreaterThanOrEqualTo(3);
    }

    @Test
    @Transactional
    void removingOneAssignmentKeepsTheOtherAndBothGlobalLabels() {
        Ticket ticket = ticket("remove");

        Label a = label("REMOVE-A");
        Label b = label("REMOVE-B");

        UUID actor = UUID.randomUUID();

        assignments.insertManualIfAbsent(
                ticket.getId(),
                a.getId(),
                actor,
                Instant.now());

        assignments.insertManualIfAbsent(
                ticket.getId(),
                b.getId(),
                actor,
                Instant.now());

        assertThat(
                assignments.deleteAssignment(
                        ticket.getId(),
                        a.getId()))
                .isOne();

        assertThat(
                assignments.findLabelsByTicketId(ticket.getId()))
                .extracting(Label::getId)
                .containsExactly(b.getId());

        assertThat(labels.existsById(a.getId())).isTrue();
        assertThat(labels.existsById(b.getId())).isTrue();
    }

    private TicketFilter filter(Set<UUID> ids) {
        return new TicketFilter(
                null,
                null,
                null,
                null,
                null,
                ids);
    }

    private Label label(String suffix) {
        Label label = new Label();

        label.setCode(
                "IT-" + suffix + "-" + UUID.randomUUID());

        label.setName(suffix);
        label.setActive(true);
        label.setCreatedAt(Instant.now());
        label.setUpdatedAt(Instant.now());

        label = labels.saveAndFlush(label);

        labelIds.add(label.getId());

        return label;
    }

    private ModuleUser moduleUser(String suffix) {
        ModuleUser user = new ModuleUser();

        user.setCitizenId(UUID.randomUUID());
        user.setFirstName("Label");
        user.setLastName("Integration " + suffix);
        user.setEmail(
                "label-integration-" +
                        UUID.randomUUID() +
                        "@example.test");

        user.setRole(ModuleRole.CITIZEN);
        user.setLastSyncedAt(Instant.now());

        user = moduleUsers.saveAndFlush(user);

        moduleUserIds.add(user.getId());

        return user;
    }

    private Ticket ticket(String suffix) {
        RequestType type = requestTypes
                .findAll()
                .stream()
                .findFirst()
                .orElseThrow();

        ModuleUser citizen =
                moduleUser(suffix);

        Ticket ticket = new Ticket();

        ticket.setPublicId(
                "LBL-" + UUID.randomUUID());

        ticket.setTrackingCodeHash(
                "label-hash-" + UUID.randomUUID());

        ticket.setCitizenId(
                citizen.getCitizenId());

        ticket.setAnonymous(false);
        ticket.setRequestType(type);
        ticket.setTicketType(type.getTicketType());
        ticket.setResponsibleAreaId(
                type.getResponsibleAreaId());

        ticket.setSummary("label " + suffix);
        ticket.setDescription("integration");

        ticket.setCurrentStatus(
                TicketStatus.REGISTERED);

        ticket.setCurrentPriority(
                Priority.LOW);

        ticket.setStatusChangedAt(
                Instant.now());

        ticket = tickets.saveAndFlush(ticket);

        ticketIds.add(ticket.getId());

        return ticket;
    }
}