package com.reclamos.backend.entity;

import com.reclamos.backend.exception.TicketStateConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TicketStateTest {
    private static final Instant BEFORE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NOW = BEFORE.plusSeconds(3600);

    // Characterization matrix reconstructed from the services before the refactor.
    // Same-state operations are queries or PROGRESS and must preserve the timestamp.
    private static final String MATRIX = """
            START_REVIEW;REGISTERED;IN_REVIEW
            CLASSIFY;IN_REVIEW;SAME
            ROUTE;IN_REVIEW;ROUTED
            CANCEL_OWNER;REGISTERED;CANCELLED
            CANCEL_ADMIN;REGISTERED IN_REVIEW PENDING_INFORMATION;CANCELLED
            CANCEL_EXTERNAL_REVIEW;REGISTERED IN_REVIEW ROUTED IN_PROGRESS PENDING_INFORMATION;CANCELLED
            CANCEL_DUPLICATE;DUPLICATE;CANCELLED
            REQUEST_INFORMATION;IN_REVIEW ROUTED IN_PROGRESS;PENDING_INFORMATION
            ANSWER_INFORMATION;PENDING_INFORMATION;IN_REVIEW
            EXPIRE_INFORMATION;PENDING_INFORMATION;CANCELLED
            REVIEW_INFORMATION;PENDING_INFORMATION;IN_REVIEW
            LINK_DUPLICATE;REGISTERED IN_REVIEW ROUTED IN_PROGRESS PENDING_INFORMATION;DUPLICATE
            ACCEPT_DUPLICATE;REGISTERED IN_REVIEW ROUTED IN_PROGRESS PENDING_INFORMATION;SAME
            CLOSE_DUPLICATE;DUPLICATE;CLOSED
            PROMOTE_DUPLICATE;DUPLICATE;IN_REVIEW
            RESOLVE_MANUALLY;IN_PROGRESS;RESOLVED
            CLOSE_RESOLUTION;RESOLVED;CLOSED
            REOPEN;RESOLVED;IN_PROGRESS
            EXTERNAL_STARTED;ROUTED;IN_PROGRESS
            EXTERNAL_PROGRESS;IN_PROGRESS;SAME
            EXTERNAL_INFORMATION_REQUIRED;ROUTED IN_PROGRESS;PENDING_INFORMATION
            EXTERNAL_RETURNED;ROUTED IN_PROGRESS;IN_REVIEW
            EXTERNAL_RESOLVED;ROUTED IN_PROGRESS;RESOLVED
            EXTERNAL_REJECTED;ROUTED IN_PROGRESS;CANCELLED
            """;

    static Stream<Arguments> transitions() {
        return MATRIX.lines().flatMap(row -> {
            String[] cells = row.split(";");
            TicketAction action = TicketAction.valueOf(cells[0]);
            Set<TicketStatus> allowed = Arrays.stream(cells[1].split(" "))
                    .map(TicketStatus::valueOf).collect(Collectors.toSet());
            return Arrays.stream(TicketStatus.values()).map(status -> Arguments.of(
                    status, action, allowed.contains(status),
                    cells[2].equals("SAME") ? status : TicketStatus.valueOf(cells[2])));
        });
    }

    @ParameterizedTest(name = "{0} / {1}: allowed={2}, destination={3}")
    @MethodSource("transitions")
    void characterizesEveryStateAndAction(TicketStatus status, TicketAction action,
                                         boolean allowed, TicketStatus destination) {
        Ticket ticket = ticket(status);
        assertEquals(allowed, ticket.canPerform(action));
        assertEquals(status, ticket.getCurrentStatus());
        assertEquals(BEFORE, ticket.getStatusChangedAt());
        if (allowed) {
            ticket.transition(action, NOW, TicketStatus.IN_REVIEW);
            assertEquals(destination, ticket.getCurrentStatus());
            assertEquals(destination == status ? BEFORE : NOW, ticket.getStatusChangedAt());
        } else {
            assertThrows(TicketStateConflictException.class,
                    () -> ticket.transition(action, NOW, TicketStatus.IN_REVIEW));
            assertEquals(status, ticket.getCurrentStatus());
            assertEquals(BEFORE, ticket.getStatusChangedAt());
        }
        assertEquals(Priority.HIGH, ticket.getCurrentPriority());
        assertEquals((short) 35, ticket.getCurrentProgress());
        assertEquals(BEFORE, ticket.getClassificationFinalizedAt());
        assertEquals(2, ticket.getReopenCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"M2", "m2", "M3", "M6"})
    void reviewChoosesManagementDestinationFromResponsibleArea(String area) {
        Ticket ticket = ticket(TicketStatus.IN_REVIEW);
        ticket.setResponsibleAreaId(area);
        TicketStatus destination = area.equalsIgnoreCase("M2") ? TicketStatus.IN_PROGRESS : TicketStatus.ROUTED;
        assertEquals(destination, ticket.nextStatus(TicketAction.ROUTE));
        ticket.transition(TicketAction.ROUTE, NOW);
        assertEquals(destination, ticket.getCurrentStatus());
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_REVIEW", "ROUTED", "IN_PROGRESS"})
    void informationRestoresPersistedResumeStatus(TicketStatus resumeStatus) {
        Ticket ticket = ticket(resumeStatus);
        ticket.transition(TicketAction.REQUEST_INFORMATION, NOW);
        // A change in area must not influence the saved return state.
        ticket.setResponsibleAreaId("M2");
        ticket.transition(TicketAction.ANSWER_INFORMATION, NOW.plusSeconds(10), resumeStatus);
        assertEquals(resumeStatus, ticket.getCurrentStatus());
        assertEquals(NOW.plusSeconds(10), ticket.getStatusChangedAt());
    }

    @Test
    void contextDelegatesAgainAfterEachTransition() {
        Ticket ticket = ticket(TicketStatus.REGISTERED);
        ticket.setResponsibleAreaId("M2");
        ticket.transition(TicketAction.START_REVIEW, NOW);
        ticket.transition(TicketAction.ROUTE, NOW.plusSeconds(1));
        ticket.transition(TicketAction.RESOLVE_MANUALLY, NOW.plusSeconds(2));
        ticket.transition(TicketAction.REOPEN, NOW.plusSeconds(3));
        ticket.transition(TicketAction.RESOLVE_MANUALLY, NOW.plusSeconds(4));
        ticket.transition(TicketAction.CLOSE_RESOLUTION, NOW.plusSeconds(5));
        assertEquals(TicketStatus.CLOSED, ticket.getCurrentStatus());
        assertFalse(ticket.canPerform(TicketAction.REOPEN));
    }

    @ParameterizedTest
    @EnumSource(UpdateTicketStatusType.class)
    void externalActionsCoverTheUnchangedContract(UpdateTicketStatusType updateType) {
        assertEquals("EXTERNAL_" + updateType.name(), TicketAction.external(updateType).name());
    }

    private Ticket ticket(TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setCurrentStatus(status);
        ticket.setStatusChangedAt(BEFORE);
        ticket.setResponsibleAreaId("M3");
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setCurrentProgress((short) 35);
        ticket.setClassificationFinalizedAt(BEFORE);
        ticket.setReopenCount(2);
        return ticket;
    }
}
