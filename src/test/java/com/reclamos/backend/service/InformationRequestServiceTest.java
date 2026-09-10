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
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private final InformationRequestExpirationService expirationService =
            mock(InformationRequestExpirationService.class);
    private InformationRequestService service;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        reset(tickets, requests, activities, expirationService);
        service = new InformationRequestService(tickets, requests, activities,
                new InformationRequestDeadlineService(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(72)),
                expirationService);
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
        AuthenticatedIdentity actor = agent();
        var result = service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Adjunte el dato", "nota"), actor);

        assertEquals(InformationRequestStatus.PENDING, result.getStatus());
        assertEquals(NOW, result.getRequestedAt());
        assertEquals(NOW.plus(Duration.ofHours(72)), result.getDueAt());
        assertEquals(TicketStatus.IN_PROGRESS, result.getResumeStatus());
        assertEquals(TicketStatus.PENDING_INFORMATION, ticket.getCurrentStatus());
        verify(requests).save(argThat(value -> value.getStatus() == InformationRequestStatus.PENDING
                && value.getResumeStatus() == TicketStatus.IN_PROGRESS
                && value.getRequestedByActorType() == ActorType.AGENT
                && actor.citizenId().toString().equals(value.getRequestedByActorId())
                && !actor.subjectId().equals(value.getRequestedByActorId())
                && "M2".equals(value.getRequestedByModuleId())));
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.INFORMATION_REQUIRED
                && value.getActorType() == ActorType.AGENT
                && actor.citizenId().toString().equals(value.getActorId())
                && "M2".equals(value.getSourceModuleId())));
    }

    @Test
    void adminCreatesPendingRequestOnSomeoneElsesTicketAsAdminActor() {
        AuthenticatedIdentity actor = admin();
        var result = service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Adjunte el dato", null), actor);

        assertEquals(InformationRequestStatus.PENDING, result.getStatus());
        verify(requests).save(argThat(value -> value.getRequestedByActorType() == ActorType.ADMIN
                && actor.citizenId().toString().equals(value.getRequestedByActorId())
                && !actor.subjectId().equals(value.getRequestedByActorId())
                && "M2".equals(value.getRequestedByModuleId())));
        verify(activities).save(argThat(value -> value.getActorType() == ActorType.ADMIN
                && actor.citizenId().toString().equals(value.getActorId())
                && "M2".equals(value.getSourceModuleId())));
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
    void rejectsCitizenAndAreaResponsibleRequesters() {
        assertThrows(UnauthorizedTicketOperationException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), citizen()));
        assertThrows(UnauthorizedTicketOperationException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), areaResponsible()));
    }

    @Test
    void rejectsAgentAndAdminWhenTheyOwnTheTicket() {
        AuthenticatedIdentity ownerAgent = identity(ModuleRole.AGENT, ticket.getCitizenId());
        AuthenticatedIdentity ownerAdmin = identity(ModuleRole.ADMIN, ticket.getCitizenId());

        assertThrows(UnauthorizedTicketOperationException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), ownerAgent));
        assertThrows(UnauthorizedTicketOperationException.class, () -> service.requestInformation(ticket.getId(),
                new CreateInformationRequest("Dato", null), ownerAdmin));
        verify(requests, never()).save(any());
    }

    @Test
    void citizenAnswersBeforeDeadlineAndResumeStatusIsRestored() {
        InformationRequest pending = pending(ticket, NOW.plusSeconds(1));
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        AuthenticatedIdentity actor = citizen();
        var result = service.answerInformation(ticket.getId(), new AnswerInformationRequest("Respuesta"), actor);

        assertEquals(InformationRequestStatus.ANSWERED, result.getStatus());
        assertEquals("Respuesta", result.getResponseMessage());
        assertEquals(NOW, result.getAnsweredAt());
        assertEquals(TicketStatus.IN_PROGRESS, ticket.getCurrentStatus());
        verify(requests).save(argThat(value -> value.getAnsweredByType() == ActorType.CITIZEN
                && actor.citizenId().toString().equals(value.getAnsweredById())
                && !actor.subjectId().equals(value.getAnsweredById())));
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.INFORMATION_PROVIDED
                && value.getActorType() == ActorType.CITIZEN
                && actor.citizenId().toString().equals(value.getActorId())
                && "M2".equals(value.getSourceModuleId())));
    }

    @Test
    void answeredCannotBeAnsweredAgainAndDeadlineIsInclusive() {
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        assertThrows(InformationRequestConflictException.class, () -> service.answerInformation(ticket.getId(),
                new AnswerInformationRequest("Otra"), citizen()));
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending(ticket, NOW)));
        assertThrows(InformationRequestExpiredException.class, () -> service.answerInformation(ticket.getId(),
                new AnswerInformationRequest("Tarde"), citizen()));
    }

    @Test
    void expirationScanDelegatesUnlockedCandidatesIndividually() {
        UUID requestId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        InformationRequestRepository.ExpirationCandidate candidate =
                mock(InformationRequestRepository.ExpirationCandidate.class);
        when(candidate.getRequestId()).thenReturn(requestId);
        when(candidate.getTicketId()).thenReturn(ticketId);
        when(requests.findExpirationCandidates(InformationRequestStatus.PENDING, NOW))
                .thenReturn(List.of(candidate));

        service.expireDueRequests();

        verify(expirationService).expireIfDue(requestId, ticketId, NOW);
    }

    @Test
    void anonymousTicketCanUsePreparedTrackingBusinessEntryPointWithoutCitizenId() {
        ticket = ticket(TicketStatus.PENDING_INFORMATION, true);
        InformationRequest pending = pending(ticket, NOW.plusSeconds(1));
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
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
        return identity(ModuleRole.AGENT, UUID.randomUUID());
    }

    private AuthenticatedIdentity admin() {
        return identity(ModuleRole.ADMIN, UUID.randomUUID());
    }

    private AuthenticatedIdentity areaResponsible() {
        return new AuthenticatedIdentity("area-responsible", UUID.randomUUID(), "Area Responsible", "M6",
                ModuleRole.AREA_RESPONSIBLE);
    }

    private AuthenticatedIdentity citizen() {
        return identity(ModuleRole.CITIZEN, ticket.getCitizenId());
    }

    private AuthenticatedIdentity identity(ModuleRole role, UUID citizenId) {
        return new AuthenticatedIdentity(role.name().toLowerCase(), citizenId, role.name(), null, role);
    }
}
