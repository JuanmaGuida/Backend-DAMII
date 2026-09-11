package com.reclamos.backend.service;

import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.CancellationReasonCode;
import com.reclamos.backend.entity.InformationRequest;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.repository.InformationRequestRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketCancellationRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InformationRequestExpirationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final InformationRequestRepository requests = mock(InformationRequestRepository.class);
    private final TicketCancellationRepository cancellations = mock(TicketCancellationRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final InformationRequestExpirationService service = new InformationRequestExpirationService(
            tickets, requests, cancellations, activities);
    private Ticket ticket;
    private InformationRequest request;

    @BeforeEach
    void setUp() {
        reset(tickets, requests, cancellations, activities);
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
        request = new InformationRequest();
        request.setId(UUID.randomUUID());
        request.setTicket(ticket);
        request.setStatus(InformationRequestStatus.PENDING);
        request.setDueAt(NOW.minusSeconds(1));
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(requests.findByIdAndTicketIdForUpdate(request.getId(), ticket.getId()))
                .thenReturn(Optional.of(request));
    }

    @Test
    void expiredPendingRequestIsCancelledAfterTicketWasLocked() {
        service.expireIfDue(request.getId(), ticket.getId(), NOW);

        assertEquals(InformationRequestStatus.EXPIRED, request.getStatus());
        assertEquals(TicketStatus.CANCELLED, ticket.getCurrentStatus());
        var order = inOrder(tickets, requests, cancellations, activities);
        order.verify(tickets).findByIdForUpdate(ticket.getId());
        order.verify(requests).findByIdAndTicketIdForUpdate(request.getId(), ticket.getId());
        verify(cancellations).save(argThat(value -> value.getReasonCode() == CancellationReasonCode.INFO_TIMEOUT
                && value.getCancelledByType() == ActorType.SYSTEM));
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.CANCELLED
                && value.getSequence() == 1));
    }

    @Test
    void revalidationIgnoresAlreadyAnsweredOrNoLongerDueRequest() {
        request.setStatus(InformationRequestStatus.ANSWERED);
        service.expireIfDue(request.getId(), ticket.getId(), NOW);

        request.setStatus(InformationRequestStatus.PENDING);
        request.setDueAt(NOW.plusSeconds(1));
        service.expireIfDue(request.getId(), ticket.getId(), NOW);

        assertEquals(TicketStatus.PENDING_INFORMATION, ticket.getCurrentStatus());
        verify(requests, never()).save(any());
        verify(tickets, never()).save(any());
        verifyNoInteractions(cancellations, activities);
    }
}
