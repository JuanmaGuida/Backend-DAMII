package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.ReopenTicketRequest;
import com.reclamos.backend.dto.request.ResolveTicketRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketResolutionConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.TicketResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TicketResolutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketResolutionRepository resolutions = mock(TicketResolutionRepository.class);
    private final TicketActivityRepository activities = mock(TicketActivityRepository.class);
    private TicketResolutionService service;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        reset(tickets, resolutions, activities);
        service = new TicketResolutionService(tickets, resolutions, activities,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(3));
        ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setResponsibleAreaId("M2");
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(resolutions.save(any())).thenAnswer(invocation -> {
            TicketResolution value = invocation.getArgument(0);
            value.setId(UUID.randomUUID());
            return value;
        });
    }

    @Test
    void ownerConfirmsResolvedTicketAndCreatesClosedActivity() {
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        ticket.setResolutionConfirmationDueAt(NOW.plusSeconds(3600));
        AuthenticatedIdentity identity = identity(ModuleRole.ADMIN, ticket.getCitizenId());

        var response = service.confirm(ticket.getId(), identity);

        assertEquals(TicketStatus.CLOSED, response.getStatus());
        assertEquals(NOW, response.getStatusChangedAt());
        assertEquals(TicketStatus.CLOSED, ticket.getCurrentStatus());
        assertNull(ticket.getResolutionConfirmationDueAt());
        verify(tickets).save(ticket);
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.CLOSED
                && value.getPreviousStatus() == TicketStatus.RESOLVED
                && value.getNewStatus() == TicketStatus.CLOSED
                && value.getActorType() == ActorType.CITIZEN
                && identity.citizenId().toString().equals(value.getActorId())
                && "M2".equals(value.getSourceModuleId())
                && "CITIZEN_CONFIRMED".equals(value.getReasonCode())
                && NOW.equals(value.getOccurredAt())));
    }

    @Test
    void ownerReopensResolvedTicketAndCreatesReopenedActivity() {
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        ticket.setReopenCount(2);
        ticket.setResolutionConfirmationDueAt(NOW.plusSeconds(3600));
        AuthenticatedIdentity identity = identity(ModuleRole.AGENT, ticket.getCitizenId());

        var response = service.reopen(ticket.getId(), new ReopenTicketRequest("El problema continúa"), identity);

        assertEquals(TicketStatus.IN_PROGRESS, response.getStatus());
        assertEquals(3, response.getReopenCount());
        assertEquals(NOW, ticket.getStatusChangedAt());
        assertNull(ticket.getResolutionConfirmationDueAt());
        verify(tickets).save(ticket);
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.REOPENED
                && value.getPreviousStatus() == TicketStatus.RESOLVED
                && value.getNewStatus() == TicketStatus.IN_PROGRESS
                && value.getActorType() == ActorType.CITIZEN
                && identity.citizenId().toString().equals(value.getActorId())
                && value.getReasonCode() == null
                && "El problema continúa".equals(value.getMessage())
                && NOW.equals(value.getOccurredAt())));
    }

    @Test
    void citizenActionsRejectMissingDifferentAndAnonymousOwners() {
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.confirm(ticket.getId(), null));
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.confirm(ticket.getId(), identity(ModuleRole.CITIZEN, UUID.randomUUID())));
        ticket.setAnonymous(true);
        ticket.setCitizenId(null);
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.reopen(ticket.getId(), new ReopenTicketRequest("Motivo"), agent()));
        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
    }

    @Test
    void citizenActionsReturnNotFoundWithoutPersistence() {
        UUID missing = UUID.randomUUID();
        when(tickets.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.confirm(missing, agent()));
        assertThrows(ResourceNotFoundException.class,
                () -> service.reopen(missing, new ReopenTicketRequest("Motivo"), agent()));
        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
    }

    @Test
    void citizenActionsRejectEveryStatusExceptResolvedWithoutEffects() {
        AuthenticatedIdentity owner = identity(ModuleRole.CITIZEN, ticket.getCitizenId());
        for (TicketStatus status : TicketStatus.values()) {
            if (status == TicketStatus.RESOLVED) continue;
            clearInvocations(tickets, activities);
            ticket.setCurrentStatus(status);
            int reopenCount = ticket.getReopenCount();
            assertThrows(TicketResolutionConflictException.class,
                    () -> service.confirm(ticket.getId(), owner), status.name());
            assertThrows(TicketResolutionConflictException.class,
                    () -> service.reopen(ticket.getId(), new ReopenTicketRequest("Motivo"), owner), status.name());
            assertEquals(reopenCount, ticket.getReopenCount());
            verify(tickets, never()).save(any());
            verify(activities, never()).save(any());
        }
    }

    @Test
    void secondCitizenActionIsRejectedAfterFirstTransition() {
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        AuthenticatedIdentity owner = identity(ModuleRole.CITIZEN, ticket.getCitizenId());
        service.reopen(ticket.getId(), new ReopenTicketRequest("Motivo"), owner);
        clearInvocations(tickets, activities);

        assertThrows(TicketResolutionConflictException.class,
                () -> service.reopen(ticket.getId(), new ReopenTicketRequest("Otro"), owner));
        assertEquals(1, ticket.getReopenCount());
        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
    }

    @Test
    void agentPersistsResolutionChangesStatusAndCreatesActivity() {
        AuthenticatedIdentity identity = identity(ModuleRole.AGENT, UUID.randomUUID());
        var response = service.resolveManually(ticket.getId(), request(), identity);

        assertNotNull(response.getResolutionId());
        assertEquals(TicketStatus.RESOLVED, response.getStatus());
        assertEquals(TicketStatus.RESOLVED, ticket.getCurrentStatus());
        assertEquals(NOW, ticket.getStatusChangedAt());
        assertEquals(NOW.plus(Duration.ofDays(3)), ticket.getResolutionConfirmationDueAt());
        verify(resolutions).save(argThat(value -> value.getTicket() == ticket
                && value.getType() == ResolutionType.ACTION_COMPLETED
                && "Trabajo finalizado".equals(value.getPublicMessage())
                && "Verificado".equals(value.getInternalMessage())
                && value.getResolvedByType() == ActorType.AGENT
                && identity.citizenId().toString().equals(value.getResolvedById())
                && !identity.subjectId().equals(value.getResolvedById())
                && "M2".equals(value.getResolvedByModuleId())
                && NOW.equals(value.getResolvedAt())));
        verify(activities).save(argThat(value -> value.getActionType() == ActivityType.RESOLVED
                && value.getPreviousStatus() == TicketStatus.IN_PROGRESS
                && value.getNewStatus() == TicketStatus.RESOLVED
                && value.getActorType() == ActorType.AGENT
                && identity.citizenId().toString().equals(value.getActorId())
                && "M2".equals(value.getSourceModuleId())
                && "ACTION_COMPLETED".equals(value.getReasonCode())
                && "Trabajo finalizado".equals(value.getMessage())
                && NOW.equals(value.getOccurredAt())));
    }

    @Test
    void adminIsRecordedAsAdmin() {
        AuthenticatedIdentity identity = identity(ModuleRole.ADMIN, UUID.randomUUID());
        service.resolveManually(ticket.getId(), request(), identity);
        verify(resolutions).save(argThat(value -> value.getResolvedByType() == ActorType.ADMIN));
        verify(activities).save(argThat(value -> value.getActorType() == ActorType.ADMIN));
    }

    @Test
    void citizenAndAreaResponsibleAreForbiddenWithoutLoadingTicket() {
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.resolveManually(ticket.getId(), request(), identity(ModuleRole.CITIZEN, UUID.randomUUID())));
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.resolveManually(ticket.getId(), request(), identity(ModuleRole.AREA_RESPONSIBLE, UUID.randomUUID())));
        verify(tickets, never()).findByIdForUpdate(any());
    }

    @Test
    void agentAndAdminCannotResolveTheirOwnCitizenTicket() {
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.resolveManually(ticket.getId(), request(), identity(ModuleRole.AGENT, ticket.getCitizenId())));
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.resolveManually(ticket.getId(), request(), identity(ModuleRole.ADMIN, ticket.getCitizenId())));
        verifyNoPersistenceAndUnchanged(TicketStatus.IN_PROGRESS);
    }

    @Test
    void externalAreaCannotBeResolvedManually() {
        ticket.setResponsibleAreaId("M6");
        assertThrows(TicketResolutionConflictException.class,
                () -> service.resolveManually(ticket.getId(), request(), agent()));
        verifyNoPersistenceAndUnchanged(TicketStatus.IN_PROGRESS);
    }

    @Test
    void everyStatusExceptInProgressIsRejectedWithoutPersistence() {
        for (TicketStatus status : TicketStatus.values()) {
            if (status == TicketStatus.IN_PROGRESS) continue;
            clearInvocations(resolutions, activities, tickets);
            ticket.setCurrentStatus(status);
            assertThrows(TicketResolutionConflictException.class,
                    () -> service.resolveManually(ticket.getId(), request(), agent()), status.name());
            verifyNoPersistenceAndUnchanged(status);
        }
    }

    @Test
    void missingTicketReturnsNotFound() {
        UUID missing = UUID.randomUUID();
        when(tickets.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.resolveManually(missing, request(), agent()));
        verifyNoInteractions(resolutions, activities);
    }

    @Test
    void rejectsNonPositiveConfirmationDurationDuringConstruction() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThrows(IllegalArgumentException.class, () -> new TicketResolutionService(
                tickets, resolutions, activities, clock, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TicketResolutionService(
                tickets, resolutions, activities, clock, Duration.ofSeconds(-1)));
    }

    private void verifyNoPersistenceAndUnchanged(TicketStatus status) {
        assertEquals(status, ticket.getCurrentStatus());
        verify(resolutions, never()).save(any());
        verify(activities, never()).save(any());
        verify(tickets, never()).save(any());
    }

    private ResolveTicketRequest request() {
        return new ResolveTicketRequest(ResolutionType.ACTION_COMPLETED, "Trabajo finalizado", "Verificado");
    }

    private AuthenticatedIdentity agent() {
        return identity(ModuleRole.AGENT, UUID.randomUUID());
    }

    private AuthenticatedIdentity identity(ModuleRole role, UUID citizenId) {
        return new AuthenticatedIdentity("subject-" + role, citizenId, role.name(), null, role);
    }
}
