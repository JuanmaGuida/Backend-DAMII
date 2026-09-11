package com.reclamos.backend.service;

import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResolutionConfirmationTimeoutServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final ResolutionConfirmationTimeoutService service =
            new ResolutionConfirmationTimeoutService(tickets, activities);
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        reset(tickets, activities);
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        ticket.setResolutionConfirmationDueAt(NOW.minusSeconds(1));
        ticket.setReopenCount(3);
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(activities.countByTicketId(ticket.getId())).thenReturn(4);
    }

    @Test
    void closesExpiredResolvedTicketAndRecordsExactlyOneSystemActivity() {
        service.closeIfConfirmationExpired(ticket.getId(), NOW);

        assertEquals(TicketStatus.CLOSED, ticket.getCurrentStatus());
        assertEquals(NOW, ticket.getStatusChangedAt());
        assertNull(ticket.getResolutionConfirmationDueAt());
        assertEquals(3, ticket.getReopenCount());
        verify(tickets).findByIdForUpdate(ticket.getId());
        verify(tickets).save(ticket);
        verify(activities, times(1)).save(argThat(activity -> activity.getTicket() == ticket
                && activity.getSequence() == 5
                && activity.getActionType() == ActivityType.CLOSED
                && activity.getPreviousStatus() == TicketStatus.RESOLVED
                && activity.getNewStatus() == TicketStatus.CLOSED
                && activity.getActorType() == ActorType.SYSTEM
                && activity.getActorId() == null
                && "M2".equals(activity.getSourceModuleId())
                && "CONFIRMATION_TIMEOUT".equals(activity.getReasonCode())
                && NOW.equals(activity.getOccurredAt())
                && activity.getMessage().contains("cerrado automáticamente")));
    }

    @Test
    void closesWhenDeadlineEqualsNowAndAlsoSupportsAnonymousTickets() {
        ticket.setResolutionConfirmationDueAt(NOW);
        ticket.setAnonymous(true);
        ticket.setCitizenId(null);

        service.closeIfConfirmationExpired(ticket.getId(), NOW);

        assertEquals(TicketStatus.CLOSED, ticket.getCurrentStatus());
        verify(tickets).save(ticket);
        verify(activities).save(any());
    }

    @Test
    void ignoresFutureOrMissingDeadline() {
        ticket.setResolutionConfirmationDueAt(NOW.plusSeconds(1));
        service.closeIfConfirmationExpired(ticket.getId(), NOW);
        assertUnchanged();

        clearInvocations(tickets, activities);
        ticket.setResolutionConfirmationDueAt(null);
        service.closeIfConfirmationExpired(ticket.getId(), NOW);
        assertUnchanged();
    }

    @Test
    void revalidatesLockedTicketAndIgnoresConfirmedOrReopenedCandidate() {
        ticket.setCurrentStatus(TicketStatus.CLOSED);
        service.closeIfConfirmationExpired(ticket.getId(), NOW);
        assertUnchanged();

        clearInvocations(tickets, activities);
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        service.closeIfConfirmationExpired(ticket.getId(), NOW);
        assertUnchanged();
    }

    @Test
    void repeatedProcessingIsIdempotent() {
        service.closeIfConfirmationExpired(ticket.getId(), NOW);
        clearInvocations(tickets, activities);

        service.closeIfConfirmationExpired(ticket.getId(), NOW.plusSeconds(60));

        verify(tickets).findByIdForUpdate(ticket.getId());
        verify(tickets, never()).save(any());
        verifyNoInteractions(activities);
    }

    @Test
    void missingCandidateIsIgnored() {
        UUID missing = UUID.randomUUID();
        when(tickets.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        service.closeIfConfirmationExpired(missing, NOW);

        verify(tickets).findByIdForUpdate(missing);
        verify(tickets, never()).save(any());
        verifyNoInteractions(activities);
    }

    private void assertUnchanged() {
        verify(tickets, never()).save(any());
        verifyNoInteractions(activities);
    }
}