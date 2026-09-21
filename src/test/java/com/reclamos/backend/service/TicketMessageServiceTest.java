package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.TicketMessageRequest;
import com.reclamos.backend.dto.response.TicketMessageResponse;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketMessage;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TicketMessageServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-21T15:00:00Z");

    private final TicketMessageRepository messageRepository = mock(TicketMessageRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketActivityRepository activityRepository = mock(TicketActivityRepository.class);
    private final TicketMessageService service = new TicketMessageService(
            messageRepository, ticketRepository, activityRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID ticketId;
    private UUID ownerId;
    private Ticket ownTicket;
    private Ticket otherTicket;
    private AuthenticatedIdentity owner;

    @BeforeEach
    void setUp() {
        ticketId = UUID.randomUUID();
        ownerId = UUID.randomUUID();

        ownTicket = new Ticket();
        ownTicket.setId(ticketId);
        ownTicket.setCitizenId(ownerId);
        ownTicket.setAnonymous(false);

        otherTicket = new Ticket();
        otherTicket.setId(ticketId);
        otherTicket.setCitizenId(UUID.randomUUID());
        otherTicket.setAnonymous(false);
        otherTicket.setResponsibleAreaId("M2");

        owner = new AuthenticatedIdentity("owner", ownerId, "Vecino", null, ModuleRole.CITIZEN);

        when(messageRepository.save(any())).thenAnswer(invocation -> {
            TicketMessage message = invocation.getArgument(0);
            message.setId(1L);
            message.setCreatedAt(NOW);
            return message;
        });
    }

    private TicketMessageRequest request(MessageVisibility visibility, String text) {
        TicketMessageRequest request = new TicketMessageRequest();
        request.setVisibility(visibility);
        request.setText(text);
        return request;
    }

    // --- create() ---

    @Test
    void ownerCanSendAPublicMessageOnTheirOwnTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ownTicket));

        TicketMessageResponse response = service.create(ticketId, request(MessageVisibility.PUBLIC, "Hola"), owner);

        ArgumentCaptor<TicketMessage> captor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(captor.capture());
        assertSame(ownTicket, captor.getValue().getTicket());
        assertEquals(ActorType.CITIZEN, captor.getValue().getAuthorType());
        assertEquals(ownerId.toString(), captor.getValue().getAuthorId());
        assertEquals("M2", captor.getValue().getSourceModuleId());
        assertEquals(MessageVisibility.PUBLIC, captor.getValue().getVisibility());
        assertEquals("Hola", captor.getValue().getText());
        assertEquals(ActorType.CITIZEN, response.authorType());
    }

    @Test
    void ownerCannotSendAnInternalMessageOnTheirOwnTicketEvenIfTheyAreStaff() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ownTicket));
        AuthenticatedIdentity ownerAsAdmin = new AuthenticatedIdentity(
                "owner", ownerId, "Vecino/Admin", null, ModuleRole.ADMIN);

        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.create(ticketId, request(MessageVisibility.INTERNAL, "nota"), ownerAsAdmin));
        verifyNoInteractions(messageRepository);
    }

    @Test
    void ownerActingAsAdminCanStillSendPublicOnTheirOwnTicketAsCitizen() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ownTicket));
        AuthenticatedIdentity ownerAsAdmin = new AuthenticatedIdentity(
                "owner", ownerId, "Vecino/Admin", null, ModuleRole.ADMIN);

        TicketMessageResponse response =
                service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), ownerAsAdmin);

        assertEquals(ActorType.CITIZEN, response.authorType());
    }

    @Test
    void agentCanSendPublicOrInternalOnAnUnrelatedTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity agent = new AuthenticatedIdentity(
                "agent", UUID.randomUUID(), "Agente", null, ModuleRole.AGENT);

        TicketMessageResponse publicResponse =
                service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), agent);
        TicketMessageResponse internalResponse =
                service.create(ticketId, request(MessageVisibility.INTERNAL, "nota interna"), agent);

        assertEquals(ActorType.AGENT, publicResponse.authorType());
        assertEquals(ActorType.AGENT, internalResponse.authorType());
        verify(messageRepository, times(2)).save(any());
    }

    @Test
    void areaResponsibleCanSendOnATicketOfTheirOwnArea() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area", UUID.randomUUID(), "Responsable", "M2", ModuleRole.AREA_RESPONSIBLE);

        TicketMessageResponse response =
                service.create(ticketId, request(MessageVisibility.INTERNAL, "nota"), areaResponsible);

        assertEquals(ActorType.AREA_RESPONSIBLE, response.authorType());
    }

    @Test
    void areaResponsibleCannotSendOnATicketOfADifferentArea() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area", UUID.randomUUID(), "Responsable", "M5", ModuleRole.AREA_RESPONSIBLE);

        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), areaResponsible));
        verifyNoInteractions(messageRepository);
    }

    @Test
    void aCitizenCannotSendMessagesOnSomeoneElsesTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity anotherCitizen = new AuthenticatedIdentity(
                "otro", UUID.randomUUID(), "Otro vecino", null, ModuleRole.CITIZEN);

        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), anotherCitizen));
        verifyNoInteractions(messageRepository);
    }

    @Test
    void nullIdentityIsForbidden() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), null));
        verifyNoInteractions(messageRepository);
    }

    @Test
    void missingTicketIsNotFound() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), owner));
        verifyNoInteractions(messageRepository);
    }

    @Test
    void createAlsoRecordsAMatchingActivityForTheGeneralTimeline() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ownTicket));
        when(activityRepository.countByTicketId(ticketId)).thenReturn(3);

        service.create(ticketId, request(MessageVisibility.PUBLIC, "hola"), owner);

        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(captor.capture());
        assertEquals(ActivityType.PUBLIC_MESSAGE_SENT, captor.getValue().getActionType());
        assertEquals(4, captor.getValue().getSequence());
        assertEquals("hola", captor.getValue().getMessage());
        assertEquals(NOW, captor.getValue().getOccurredAt());
    }

    @Test
    void internalMessageIsRecordedAsInternalMessageAddedActivity() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity agent = new AuthenticatedIdentity(
                "agent", UUID.randomUUID(), "Agente", null, ModuleRole.AGENT);

        service.create(ticketId, request(MessageVisibility.INTERNAL, "nota"), agent);

        ArgumentCaptor<TicketActivity> captor = ArgumentCaptor.forClass(TicketActivity.class);
        verify(activityRepository).save(captor.capture());
        assertEquals(ActivityType.INTERNAL_MESSAGE_ADDED, captor.getValue().getActionType());
    }

    // --- list() ---

    @Test
    void ownerOnlySeesPublicMessages() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ownTicket));
        when(messageRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(ticketId, MessageVisibility.PUBLIC))
                .thenReturn(List.of(existingMessage(MessageVisibility.PUBLIC)));

        List<TicketMessageResponse> messages = service.list(ticketId, owner);

        assertEquals(1, messages.size());
        verify(messageRepository, never()).findAllByTicket_IdOrderByCreatedAtAsc(any());
    }

    @Test
    void agentSeesPublicAndInternalMessages() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity agent = new AuthenticatedIdentity(
                "agent", UUID.randomUUID(), "Agente", null, ModuleRole.AGENT);
        when(messageRepository.findAllByTicket_IdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(existingMessage(MessageVisibility.PUBLIC), existingMessage(MessageVisibility.INTERNAL)));

        List<TicketMessageResponse> messages = service.list(ticketId, agent);

        assertEquals(2, messages.size());
        verify(messageRepository, never())
                .findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void areaResponsibleFromADifferentAreaCannotListMessages() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity areaResponsible = new AuthenticatedIdentity(
                "area", UUID.randomUUID(), "Responsable", "M5", ModuleRole.AREA_RESPONSIBLE);

        assertThrows(UnauthorizedTicketOperationException.class, () -> service.list(ticketId, areaResponsible));
    }

    @Test
    void aCitizenCannotListMessagesOfSomeoneElsesTicket() {
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(otherTicket));
        AuthenticatedIdentity anotherCitizen = new AuthenticatedIdentity(
                "otro", UUID.randomUUID(), "Otro vecino", null, ModuleRole.CITIZEN);

        assertThrows(UnauthorizedTicketOperationException.class, () -> service.list(ticketId, anotherCitizen));
    }

    private TicketMessage existingMessage(MessageVisibility visibility) {
        TicketMessage message = new TicketMessage();
        message.setId(9L);
        message.setTicket(ownTicket);
        message.setAuthorType(ActorType.CITIZEN);
        message.setAuthorId(ownerId.toString());
        message.setSourceModuleId("M2");
        message.setVisibility(visibility);
        message.setText("texto");
        message.setCreatedAt(NOW);
        return message;
    }
}
