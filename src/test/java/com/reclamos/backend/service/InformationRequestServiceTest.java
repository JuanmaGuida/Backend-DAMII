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
import org.springframework.mock.web.MockMultipartFile;

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
    private final TicketSlaService ticketSlaService = mock(TicketSlaService.class);
    private final TicketOutboxService outbox = mock(TicketOutboxService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final InformationRequestAttachmentRepository requestAttachments =
            mock(InformationRequestAttachmentRepository.class);
    private InformationRequestService service;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        reset(tickets, requests, activities, expirationService, ticketSlaService, outbox,
                attachmentService, requestAttachments);
        service = new InformationRequestService(tickets, requests, activities,
                new InformationRequestDeadlineService(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(72)),
                expirationService, ticketSlaService, outbox, attachmentService, requestAttachments);
        lenient().when(attachmentService.validate(any())).thenReturn(List.of());
        lenient().when(attachmentService.storeForTicket(any(), any(), anyList(), any())).thenReturn(List.of());
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
        verify(ticketSlaService).pauseActiveResolutionCycle(ticket, NOW);
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
        pending.setRequestedByModuleId("M2");
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
        verify(ticketSlaService).resumeActiveResolutionCycle(ticket, NOW);
        verify(outbox).informationProvided(ticket, "Respuesta", List.of(), ActorType.CITIZEN,
                actor.citizenId().toString(), false, NOW);
    }

    @Test
    void cancelledInformationRequestCannotReactivateCancelledTicket() {
        ticket.setCurrentStatus(TicketStatus.CANCELLED);
        InformationRequest cancelled = pending(ticket, NOW.plusSeconds(1));
        cancelled.setStatus(InformationRequestStatus.CANCELLED);

        assertThrows(InformationRequestConflictException.class, () -> service.answerInformation(ticket.getId(),
                new AnswerInformationRequest("Respuesta tardía"), citizen()));

        assertEquals(TicketStatus.CANCELLED, ticket.getCurrentStatus());
        assertEquals(InformationRequestStatus.CANCELLED, cancelled.getStatus());
        verify(requests, never()).findByTicketIdAndStatusForUpdate(any(), any());
        verify(requests, never()).save(any());
        verify(ticketSlaService, never()).resumeActiveResolutionCycle(any(), any());
        verifyNoInteractions(activities, outbox);
    }

    @Test
    void legacyPendingRequestOnCancelledTicketIsRejectedBeforeRestoringResumeStatus() {
        ticket.setCurrentStatus(TicketStatus.CANCELLED);
        InformationRequest inconsistentPending = pending(ticket, NOW.plusSeconds(1));
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(inconsistentPending));

        assertThrows(InformationRequestConflictException.class, () -> service.answerInformation(ticket.getId(),
                new AnswerInformationRequest("Respuesta tardía"), citizen()));

        assertEquals(TicketStatus.CANCELLED, ticket.getCurrentStatus());
        assertEquals(InformationRequestStatus.PENDING, inconsistentPending.getStatus());
        verify(requests, never()).findByTicketIdAndStatusForUpdate(any(), any());
        verify(requests, never()).save(any());
        verify(ticketSlaService, never()).resumeActiveResolutionCycle(any(), any());
        verifyNoInteractions(activities, outbox);
    }

    @Test
    void answeredCannotBeAnsweredAgainAndDeadlineIsInclusive() {
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
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
        pending.setRequestedByModuleId("M6");
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));

        assertDoesNotThrow(() -> service.answerAnonymousFromTracking(
                ticket.getId(), new AnswerInformationRequest("Respuesta")));
        assertEquals(InformationRequestStatus.ANSWERED, pending.getStatus());
        assertNull(pending.getAnsweredById());
        verify(activities).save(argThat(activity -> activity.getActorType() == ActorType.CITIZEN
                && activity.getActorId() == null));
        verify(ticketSlaService).resumeActiveResolutionCycle(ticket, NOW);
        verify(outbox).informationProvided(ticket, "Respuesta", List.of(), ActorType.CITIZEN,
                null, true, NOW);
    }

    @Test
    void externalRequiredByCanShortenButNeverExtendM2Deadline() {
        ticket.setCurrentStatus(TicketStatus.ROUTED);

        InformationRequest shorter = service.requestInformationFromExternal(ticket, "M6",
                ActorType.EXTERNAL_USER, "external-1", "Dato", null, NOW,
                NOW.plus(Duration.ofHours(24)), UUID.randomUUID());
        assertEquals(NOW.plus(Duration.ofHours(24)), shorter.getDueAt());

        ticket.setCurrentStatus(TicketStatus.ROUTED);
        InformationRequest later = service.requestInformationFromExternal(ticket, "M6",
                ActorType.EXTERNAL_USER, "external-1", "Dato", null, NOW,
                NOW.plus(Duration.ofHours(96)), UUID.randomUUID());
        assertEquals(NOW.plus(Duration.ofHours(72)), later.getDueAt());
    }

    @Test
    void externalRequiredByMustBeFuture() {
        ticket.setCurrentStatus(TicketStatus.ROUTED);
        assertThrows(InvalidTicketRequestException.class, () -> service.requestInformationFromExternal(
                ticket, "M6", ActorType.SYSTEM, null, "Dato", null, NOW, NOW, UUID.randomUUID()));
    }

    @Test
    void citizenCanAnswerWithFilesOnlyAndLinksResponseAttachment() {
        InformationRequest pending = pending(ticket, NOW.plusSeconds(1));
        pending.setRequestedByModuleId("M6");
        ticket.setCurrentStatus(TicketStatus.PENDING_INFORMATION);
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));
        var file = new MockMultipartFile("attachments", "proof.png", "image/png", new byte[]{1});
        var validated = new AttachmentService.ValidatedAttachment(file, "proof.png", "image/png", 1);
        when(attachmentService.validate(any())).thenReturn(List.of(validated));
        Attachment stored = new Attachment();
        stored.setId(10L);
        stored.setTicket(ticket);
        stored.setFileName("proof.png");
        stored.setContentType("image/png");
        stored.setSizeBytes(1);
        stored.setVisibility(MessageVisibility.PUBLIC);
        stored.setCreatedAt(NOW);
        when(attachmentService.storeForTicket(eq(ticket), any(), eq(List.of(validated)), eq(NOW)))
                .thenReturn(List.of(stored));

        var response = service.answerInformation(ticket.getId(), new AnswerInformationRequest(null), citizen(),
                new org.springframework.web.multipart.MultipartFile[]{file});

        assertNull(response.getResponseMessage());
        assertEquals(1, response.getAttachments().size());
        verify(requestAttachments).saveAll(argThat(links -> {
            InformationRequestAttachment link = links.iterator().next();
            return link.getInformationRequest() == pending && link.getAttachment() == stored
                    && link.getRole() == InformationAttachmentRole.RESPONSE;
        }));
        verify(outbox).informationProvided(eq(ticket), isNull(), eq(List.of(stored)),
                eq(ActorType.CITIZEN), eq(ticket.getCitizenId().toString()), eq(true), eq(NOW));
    }

    @Test
    void anonymousCitizenCanAnswerWithTextAndFilesUsingNullActorId() {
        ticket = ticket(TicketStatus.PENDING_INFORMATION, true);
        InformationRequest pending = pending(ticket, NOW.plusSeconds(1));
        pending.setRequestedByModuleId("M6");
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        when(requests.findByTicketIdAndStatusForUpdate(ticket.getId(), InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(pending));
        var file = new MockMultipartFile("attachments", "proof.pdf", "application/pdf", new byte[]{1});
        var validated = new AttachmentService.ValidatedAttachment(file, "proof.pdf", "application/pdf", 1);
        when(attachmentService.validate(any())).thenReturn(List.of(validated));
        Attachment stored = new Attachment();
        stored.setId(11L);
        stored.setTicket(ticket);
        stored.setFileName("proof.pdf");
        stored.setContentType("application/pdf");
        stored.setSizeBytes(1);
        stored.setVisibility(MessageVisibility.PUBLIC);
        stored.setCreatedAt(NOW);
        when(attachmentService.storeForTicket(eq(ticket), isNull(), eq(List.of(validated)), eq(NOW)))
                .thenReturn(List.of(stored));

        var response = service.answerAnonymousFromTracking(ticket.getId(),
                new AnswerInformationRequest("  detalle  "),
                new org.springframework.web.multipart.MultipartFile[]{file});

        assertEquals("detalle", response.getResponseMessage());
        assertNull(pending.getAnsweredById());
        verify(attachmentService).storeForTicket(ticket, null, List.of(validated), NOW);
        verify(requestAttachments).saveAll(argThat(links -> links.iterator().next().getRole()
                == InformationAttachmentRole.RESPONSE));
        verify(outbox).informationProvided(ticket, "detalle", List.of(stored), ActorType.CITIZEN,
                null, true, NOW);
    }

    @Test
    void emptyTextAndNoAttachmentsAreRejectedBeforeLockingTicket() {
        when(attachmentService.validate(any())).thenReturn(List.of());
        assertThrows(InvalidTicketRequestException.class, () -> service.answerInformation(
                ticket.getId(), new AnswerInformationRequest("  "), citizen(),
                new org.springframework.web.multipart.MultipartFile[0]));
        verify(tickets, never()).findByIdForUpdate(any());
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
