package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.SatisfactionSurveyRequest;
import com.reclamos.backend.entity.SatisfactionSurvey;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.SatisfactionSurveyConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.SatisfactionSurveyRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SatisfactionSurveyServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T15:00:00Z");
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final SatisfactionSurveyRepository surveyRepository = mock(SatisfactionSurveyRepository.class);
    private final SatisfactionSurveyService service = new SatisfactionSurveyService(
            ticketRepository, surveyRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    private UUID ticketId;
    private UUID ownerId;
    private Ticket ticket;
    private AuthenticatedIdentity owner;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setCitizenId(ownerId);
        ticket.setCurrentStatus(TicketStatus.CLOSED);
        owner = new AuthenticatedIdentity("owner", ownerId, "Vecino", null, ModuleRole.CITIZEN);
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
        when(surveyRepository.save(any())).thenAnswer(invocation -> {
            SatisfactionSurvey survey = invocation.getArgument(0);
            survey.setId(7L);
            return survey;
        });
    }

    @Test
    void ownerCreatesSurveyWithServerTimestampAndAllFields() {
        var response = service.create(ticketId, new SatisfactionSurveyRequest((short) 5, "Excelente"), owner);

        ArgumentCaptor<SatisfactionSurvey> captor = ArgumentCaptor.forClass(SatisfactionSurvey.class);
        verify(surveyRepository).save(captor.capture());
        assertSame(ticket, captor.getValue().getTicket());
        assertEquals((short) 5, captor.getValue().getScore());
        assertEquals("Excelente", captor.getValue().getComment());
        assertEquals(NOW, captor.getValue().getCreatedAt());
        assertEquals(7L, response.getId());
        assertEquals(ticketId, response.getTicketId());
    }

    @Test
    void nullCommentIsAccepted() {
        var response = service.create(ticketId, new SatisfactionSurveyRequest((short) 4, null), owner);
        assertNull(response.getComment());
        verify(surveyRepository).save(any());
    }

    @Test
    void missingTicketIsNotFound() {
        when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.create(ticketId, new SatisfactionSurveyRequest((short) 5, null), owner));
        verifyNoInteractions(surveyRepository);
    }

    @Test
    void nullIdentityIsForbidden() {
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.create(ticketId, new SatisfactionSurveyRequest((short) 5, null), null));
        verifyNoInteractions(surveyRepository);
    }

    @Test
    void differentOwnerIsForbiddenEvenForStaff() {
        AuthenticatedIdentity staff = new AuthenticatedIdentity("admin", UUID.randomUUID(), "Admin", null,
                ModuleRole.ADMIN);
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.create(ticketId, new SatisfactionSurveyRequest((short) 5, null), staff));
        verifyNoInteractions(surveyRepository);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = "CLOSED", mode = EnumSource.Mode.EXCLUDE)
    void everyStatusOtherThanClosedConflictsWithoutPersisting(TicketStatus status) {
        ticket.setCurrentStatus(status);
        assertThrows(SatisfactionSurveyConflictException.class,
                () -> service.create(ticketId, new SatisfactionSurveyRequest((short) 3, null), owner));
        verify(surveyRepository, never()).save(any());
    }

    @Test
    void existingSurveyConflictsWithoutPersisting() {
        when(surveyRepository.existsByTicket_Id(ticketId)).thenReturn(true);
        SatisfactionSurveyConflictException error = assertThrows(SatisfactionSurveyConflictException.class,
                () -> service.create(ticketId, new SatisfactionSurveyRequest((short) 5, null), owner));
        assertEquals("El ticket ya posee una encuesta de satisfacción registrada", error.getMessage());
        verify(surveyRepository, never()).save(any());
    }
}