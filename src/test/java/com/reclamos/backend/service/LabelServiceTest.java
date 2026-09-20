package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AssignLabelsRequest;
import com.reclamos.backend.dto.request.CreateLabelRequest;
import com.reclamos.backend.dto.request.UpdateLabelRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LabelServiceTest {
    @Mock LabelRepository labels;
    @Mock TicketLabelRepository assignments;
    @Mock TicketRepository tickets;
    @Mock TicketService ticketService;
    Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);
    LabelService service;

    @BeforeEach void setUp() { service = new LabelService(labels, assignments, tickets, ticketService, clock); }

    @Test void createsActiveTrimmedLabel() {
        when(labels.saveAndFlush(any())).thenAnswer(invocation -> {
            Label value = invocation.getArgument(0); value.setId(UUID.randomUUID()); return value;
        });
        var response = service.create(new CreateLabelRequest(" urgente ", " Urgente ", "  prioridad "));
        assertThat(response.getCode()).isEqualTo("URGENTE");
        assertThat(response.getName()).isEqualTo("Urgente");
    }

    @Test void rejectsDuplicateCode() {
        when(labels.existsByCodeIgnoreCase("URGENTE")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateLabelRequest("URGENTE", "Urgente", null)))
                .isInstanceOf(LabelConflictException.class);
    }

    @Test void assignsEveryActiveLabelAsManualWithActorAndTimestamp() {
        UUID ticketId = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        Ticket ticket = new Ticket(); ticket.setId(ticketId);
        Label one = active(first), two = active(second);
        AuthenticatedIdentity actor = new AuthenticatedIdentity("agent", UUID.randomUUID(), "Agent", null, ModuleRole.AGENT);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(labels.findAllById(any())).thenReturn(List.of(one, two));
        service.assign(ticketId, new AssignLabelsRequest(Set.of(first, second)), actor);
        verify(ticketService).requireTriageAuthority(ticket, actor);
        verify(assignments).insertManualIfAbsent(ticketId, first, actor.citizenId(), clock.instant());
        verify(assignments).insertManualIfAbsent(ticketId, second, actor.citizenId(), clock.instant());
    }

    @Test void validatesAllLabelsBeforeWritingAnything() {
        UUID ticketId = UUID.randomUUID(); Ticket ticket = new Ticket(); ticket.setId(ticketId);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(labels.findAllById(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.assign(ticketId, new AssignLabelsRequest(Set.of(UUID.randomUUID())),
                new AuthenticatedIdentity("a", UUID.randomUUID(), "A", null, ModuleRole.AGENT)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(assignments);
    }

    @Test void updatesActivationAndDescriptionWithoutChangingCode() {
        UUID id = UUID.randomUUID(); Label label = active(id); label.setCode("STABLE"); label.setName("Old");
        when(labels.findById(id)).thenReturn(Optional.of(label)); when(labels.save(label)).thenReturn(label);
        var response = service.update(id, new UpdateLabelRequest(" New ", " description ", false));
        assertThat(response.getCode()).isEqualTo("STABLE");
        assertThat(response.getName()).isEqualTo("New"); assertThat(response.getDescription()).isEqualTo("description");
    }

    @Test void deletesOnlyUnreferencedLabel() {
        UUID id = UUID.randomUUID(); Label label = active(id); when(labels.findById(id)).thenReturn(Optional.of(label));
        service.delete(id);
        verify(labels).delete(label);
    }

    @Test void referencedLabelCannotBeDeleted() {
        UUID id = UUID.randomUUID(); Label label = active(id); when(labels.findById(id)).thenReturn(Optional.of(label));
        when(assignments.existsById_LabelId(id)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(LabelConflictException.class);
        verify(labels, never()).delete(any());
    }

    @Test void inactiveLabelRollsBackBeforeAnyInsert() {
        UUID ticketId = UUID.randomUUID(), activeId = UUID.randomUUID(), inactiveId = UUID.randomUUID();
        Ticket ticket = new Ticket(); ticket.setId(ticketId); Label active = active(activeId), inactive = active(inactiveId); inactive.setActive(false);
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket)); when(labels.findAllById(any())).thenReturn(List.of(active, inactive));
        assertThatThrownBy(() -> service.assign(ticketId, new AssignLabelsRequest(Set.of(activeId, inactiveId)), actor()))
                .isInstanceOf(LabelConflictException.class);
        verifyNoInteractions(assignments);
    }

    @Test void ownershipFailureFromCentralPolicyPreventsAssignment() {
        UUID ticketId = UUID.randomUUID(); Ticket ticket = new Ticket(); ticket.setId(ticketId); var actor = actor();
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        doThrow(new UnauthorizedTicketOperationException()).when(ticketService).requireTriageAuthority(ticket, actor);
        assertThatThrownBy(() -> service.assign(ticketId, new AssignLabelsRequest(Set.of(UUID.randomUUID())), actor))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        verifyNoInteractions(assignments);
    }

    @Test void removingOneAssignmentIsIdempotentAndDoesNotDeleteGlobalLabel() {
        UUID ticketId = UUID.randomUUID(), labelId = UUID.randomUUID(); Ticket ticket = new Ticket(); ticket.setId(ticketId); var actor = actor();
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket)); when(assignments.deleteAssignment(ticketId, labelId)).thenReturn(0);
        service.remove(ticketId, labelId, actor);
        verify(assignments).deleteAssignment(ticketId, labelId);
        verify(labels, never()).delete(any());
        verifyNoMoreInteractions(assignments);
    }

    private Label active(UUID id) { Label label = new Label(); label.setId(id); label.setActive(true); return label; }
    private AuthenticatedIdentity actor() { return new AuthenticatedIdentity("agent", UUID.randomUUID(), "Agent", null, ModuleRole.AGENT); }
}