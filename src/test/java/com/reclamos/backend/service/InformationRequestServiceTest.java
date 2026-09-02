package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InformationRequestServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final InformationRequestRepository requests = mock(InformationRequestRepository.class);
    private final TicketCancellationRepository cancellations = mock(TicketCancellationRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private InformationRequestService service;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        reset(tickets, requests, cancellations, activities);
        service = new InformationRequestService(tickets, requests, cancellations, activities,
                new InformationRequestDeadlineService(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(72)));
        ticket = ticket(TicketStatus.IN_PROGRESS, false);
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(requests.save(any())).thenAnswer(invocation -> {
            InformationRequest value = invocation.getArgument(0);
            if (value.getId() == null) value.setId(UUID.randomUUID());
            return value;
        });
    }

    @Test
    void agentCreatesPendingRequestAndActivityAndChangesTicketStatus() {
        var result = service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Adjunte el dato", "nota"), agent());

        assertEquals(InformationRequestStatus.PENDING, result.getStatus());
        assertEquals(NOW, result.getRequestedAt());
        assertEquals(NOW.plus(Duration.ofHours(72)), result.getDueAt());
        assertEquals(TicketStatus.IN_PROGRESS, result.getResumeStatus());
        assertEquals(TicketStatus.PENDING_INFORMATION, ticket.getCurrentStatus());
        verify(requests).save(argThat(value -> value.getStatus() == InformationRequestStatus.PENDING
                && value.getResumeStatus() == TicketStatus.IN_PROGRESS));
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.INFORMATION_REQUIRED));
    }

    @Test
    void rejectsIncompatibleStatusAndSecondPendingRequest() {
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        assertThrows(InformationRequestConflictException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), agent()));
        ticket.setCurrentStatus(TicketStatus.ROUTED);
        when(requests.existsByTicketIdAndStatus(ticket.getId(), InformationRequestStatus.PENDING)).thenReturn(true);
        assertThrows(InformationRequestConflictException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), agent()));
    }

    @Test
    void rejectsUnauthorizedRequester() {
        assertThrows(UnauthorizedTicketOperationException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), citizen()));
    }

    @Test
    void citizenAnswersBeforeDeadlineAndResumeStatusIsRestored() {
        InformationRequest pending = pending(ticket, NOW.plusSeconds(1));
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
        when(requests.findByTicketIdAndStatus(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        var result = service.answerInformation(ticket.getId(), new AnswerInformationRequest("Respuesta"), citizen());

        assertEquals(InformationRequestStatus.ANSWERED, result.getStatus());
        assertEquals("Respuesta", result.getResponseMessage());
        assertEquals(NOW, result.getAnsweredAt());
        assertEquals(TicketStatus.IN_PROGRESS, ticket.getCurrentStatus());
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.INFORMATION_PROVIDED));
    }

    @Test
    void answeredCannotBeAnsweredAgainAndDeadlineIsInclusive() {
        when(requests.findByTicketIdAndStatus(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        assertThrows(InformationRequestConflictException.class, () -> service.answerInformation(ticket.getId(),
                new AnswerInformationRequest("Otra"), citizen()));
        when(requests.findByTicketIdAndStatus(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending(ticket, NOW)));
        assertThrows(InformationRequestExpiredException.class, () -> service.answerInformation(ticket.getId(),
                new AnswerInformationRequest("Tarde"), citizen()));
    }

    @Test
    void expiredPendingIsCancelledOnceWithTimeoutReasonWhileAnsweredIsIgnored() {
        InformationRequest expired = pending(ticket, NOW.minusSeconds(1));
        InformationRequest answered = pending(ticket, NOW.minusSeconds(1));
        answered.setStatus(InformationRequestStatus.ANSWERED);
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
        when(requests.findByStatusAndDueAtLessThanEqual(InformationRequestStatus.PENDING, NOW))
                .thenReturn(List.of(expired), List.of());

        service.expireDueRequests();
        service.expireDueRequests();

        assertEquals(InformationRequestStatus.EXPIRED, expired.getStatus());
        assertEquals(TicketStatus.CANCELLED, ticket.getCurrentStatus());
        verify(cancellations, times(1)).save(argThat(value -> value.getReasonCode() == CancellationReasonCode.INFO_TIMEOUT));
        verify(activities, times(1)).save(argThat(value -> value.getActionType() == ActivityType.CANCELLED
                && "INFO_TIMEOUT".equals(value.getReasonCode())));
        assertEquals(InformationRequestStatus.ANSWERED, answered.getStatus());
    }

    @Test
    void anonymousTicketCanUsePreparedTrackingBusinessEntryPointWithoutCitizenId() {
        ticket = ticket(TicketStatus.PENDING_INFORMATION, true);
        InformationRequest pending = pending(ticket, NOW.plusSeconds(1));
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(requests.findByTicketIdAndStatus(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        assertDoesNotThrow(() -> service.answerAnonymousFromTracking(ticket.getId(), "Respuesta", "tracking"));
        assertEquals(InformationRequestStatus.ANSWERED, pending.getStatus());
    }

    private InformationRequest pending(Ticket owner, Instant dueAt) {
        InformationRequest value = new InformationRequest();
        value.setId(UUID.randomUUID());
        value.setTicket(owner);
        value.setMessageForCitizen("Dato");
        value.setResumeStatus(TicketStatus.IN_PROGRESS);
        value.setStatus(InformationRequestStatus.PENDING);
        value.setRequestedAt(NOW.minusSeconds(1));
        value.setDueAt(dueAt);
        return value;
    }

    private Ticket ticket(TicketStatus status, boolean anonymous) {
        Ticket value = new Ticket();
        value.setId(UUID.randomUUID());
        value.setAnonymous(anonymous);
        value.setCitizenId(anonymous ? null : UUID.randomUUID());
        value.setCurrentStatus(status);
        return value;
    }

    private AuthenticatedIdentity agent() {
        return new AuthenticatedIdentity("agent", UUID.randomUUID(), "Agent", null, Set.of(ModuleRole.AGENT));
    }

    private AuthenticatedIdentity citizen() {
        return new AuthenticatedIdentity("citizen", ticket.getCitizenId(), "Citizen", null, Set.of());
    }
}