package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.LinkDuplicateRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuplicateTicketServiceTest {
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final TicketSlaService sla = mock(TicketSlaService.class);
    private final TicketOutboxService outbox = mock(TicketOutboxService.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final Instant now = Instant.parse("2026-09-21T12:00:00Z");
    private DuplicateTicketService service;
    private AuthenticatedIdentity agent;

    @BeforeEach
    void setUp() {
        service = new DuplicateTicketService(tickets, activities, sla, outbox, ticketService,
                Clock.fixed(now, ZoneOffset.UTC));
        agent = new AuthenticatedIdentity("agent", UUID.randomUUID(), "Agent", "M2", ModuleRole.AGENT);
        when(activities.countByTicketId(any())).thenReturn(1);
    }

    @Test
    void linksAtomicallyWithoutChangingAffectedCountAndEmitsTraceAndEvent() {
        Ticket duplicate = ticket(TicketStatus.REGISTERED, 3);
        Ticket main = ticket(TicketStatus.IN_PROGRESS, 845);
        arrangeLocks(duplicate, main);

        var result = service.link(duplicate.getId(), new LinkDuplicateRequest(main.getId()), agent);

        assertEquals(main.getId(), result.getMainTicketId());
        assertEquals(TicketStatus.DUPLICATE, duplicate.getCurrentStatus());
        assertSame(main, duplicate.getMainTicket());
        assertEquals(845, main.getEstimatedAffectedCount(), "los afectados no cuentan duplicados");
        verify(sla).stopActiveCyclesForDuplicate(duplicate, now);
        verify(sla, never()).stopActiveCyclesForDuplicate(eq(main), any());
        verify(sla, never()).startFirstResponseCycle(eq(duplicate), any());
        verify(sla, never()).startInitialResolutionCycle(eq(duplicate), any());
        verify(activities).save(argThat(a -> a.getActionType() == ActivityType.DUPLICATE_LINKED));
        verify(outbox).duplicateLinked(duplicate, now);
    }

    @Test
    void repeatedSameLinkIsIdempotentAndNeverDuplicatesEffects() {
        Ticket main = ticket(TicketStatus.IN_REVIEW, 100);
        Ticket duplicate = ticket(TicketStatus.DUPLICATE, 2);
        duplicate.setMainTicket(main);
        arrangeLocks(duplicate, main);

        service.link(duplicate.getId(), new LinkDuplicateRequest(main.getId()), agent);

        verifyNoInteractions(sla, outbox);
        verify(activities, never()).save(any());
    }

    @Test
    void rejectsRelinkingAndInvalidOrChainedMain() {
        Ticket oldMain = ticket(TicketStatus.REGISTERED, 1);
        Ticket duplicate = ticket(TicketStatus.DUPLICATE, 1);
        duplicate.setMainTicket(oldMain);
        Ticket main = ticket(TicketStatus.REGISTERED, 1);
        arrangeLocks(duplicate, main);
        assertThrows(TicketStateConflictException.class,
                () -> service.link(duplicate.getId(), new LinkDuplicateRequest(main.getId()), agent));

        duplicate.setMainTicket(null);
        main.setMainTicket(oldMain);
        assertThrows(TicketStateConflictException.class,
                () -> service.link(duplicate.getId(), new LinkDuplicateRequest(main.getId()), agent));
    }

    @Test
    void rejectsEveryTerminalMainAndSelfLink() {
        Ticket duplicate = ticket(TicketStatus.REGISTERED, 1);
        for (TicketStatus status : List.of(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED)) {
            Ticket main = ticket(status, 1);
            arrangeLocks(duplicate, main);
            assertThrows(TicketStateConflictException.class,
                    () -> service.link(duplicate.getId(), new LinkDuplicateRequest(main.getId()), agent));
        }
        assertThrows(TicketStateConflictException.class,
                () -> service.link(duplicate.getId(), new LinkDuplicateRequest(duplicate.getId()), agent));
    }

    @Test
    void closedPrincipalClosesOnlyItsActiveDuplicatesAndKeepsRelationship() {
        Ticket main = ticket(TicketStatus.CLOSED, 200);
        Ticket first = ticket(TicketStatus.DUPLICATE, 1); first.setMainTicket(main);
        Ticket second = ticket(TicketStatus.DUPLICATE, 1); second.setMainTicket(main);
        when(tickets.findActiveDuplicatesForUpdate(main.getId())).thenReturn(List.of(first, second));

        service.propagateClosed(main, ActorType.SYSTEM, null, "CONFIRMATION_TIMEOUT", now);

        assertAll(() -> assertEquals(TicketStatus.CLOSED, first.getCurrentStatus()),
                () -> assertEquals(TicketStatus.CLOSED, second.getCurrentStatus()),
                () -> assertSame(main, first.getMainTicket()), () -> assertSame(main, second.getMainTicket()));
        verify(activities, times(2)).save(argThat(a -> a.getActionType() == ActivityType.CLOSED));
        verify(outbox, times(2)).closed(any(), eq("CONFIRMATION_TIMEOUT"), eq(now));
    }

    @Test
    void resolvedAndIntermediatePrincipalStatesDoNotPropagate() {
        verify(tickets, never()).findActiveDuplicatesForUpdate(any());
        verifyNoInteractions(activities, outbox);
    }

    private Ticket ticket(TicketStatus status, int affected) {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setCurrentStatus(status);
        ticket.setEstimatedAffectedCount(affected);
        return ticket;
    }

    private void arrangeLocks(Ticket duplicate, Ticket main) {
        when(tickets.findByIdForUpdate(duplicate.getId())).thenReturn(Optional.of(duplicate));
        when(tickets.findByIdForUpdate(main.getId())).thenReturn(Optional.of(main));
    }
}