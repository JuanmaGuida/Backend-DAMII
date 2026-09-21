package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.InformationRequestAttachmentRepository;
import com.reclamos.backend.repository.InformationRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InformationRequestProjectionServiceTest {
    private final InformationRequestRepository requests = mock(InformationRequestRepository.class);
    private final InformationRequestAttachmentRepository attachments =
            mock(InformationRequestAttachmentRepository.class);
    private final AttachmentReferenceService attachmentReferenceService = mock(AttachmentReferenceService.class);
    private final InformationRequestProjectionService service =
            new InformationRequestProjectionService(requests, attachments, attachmentReferenceService);

    @Test
    void pendingProjectionExposesOnlyPublicRequestContextToCitizenAndKeepsStaffContext() {
        UUID ticketId = UUID.randomUUID();
        InformationRequest request = new InformationRequest();
        request.setId(UUID.randomUUID());
        request.setStatus(InformationRequestStatus.PENDING);
        request.setMessageForCitizen("Adjunte una foto");
        request.setInternalMessage("Contexto privado");
        request.setRequestedByModuleId("M6");
        request.setRequestedByActorType(ActorType.EXTERNAL_USER);
        request.setRequestedByActorId("internal-actor");
        request.setResumeStatus(TicketStatus.IN_PROGRESS);
        request.setRequestedAt(Instant.parse("2026-09-20T10:00:00Z"));
        request.setDueAt(Instant.parse("2026-09-22T10:00:00Z"));
        when(requests.findByTicketIdAndStatus(ticketId, InformationRequestStatus.PENDING))
                .thenReturn(Optional.of(request));
        Attachment contextAttachment = new Attachment();
        contextAttachment.setId(7L);
        contextAttachment.setFileName("context.pdf");
        contextAttachment.setContentType("application/pdf");
        contextAttachment.setSizeBytes(12);
        contextAttachment.setVisibility(MessageVisibility.PUBLIC);
        contextAttachment.setCreatedAt(request.getRequestedAt());
        Attachment internalAttachment = new Attachment();
        internalAttachment.setId(8L);
        internalAttachment.setFileName("internal-context.pdf");
        internalAttachment.setContentType("application/pdf");
        internalAttachment.setSizeBytes(24);
        internalAttachment.setVisibility(MessageVisibility.INTERNAL);
        internalAttachment.setCreatedAt(request.getRequestedAt());
        InformationRequestAttachment publicLink = link(request, contextAttachment);
        InformationRequestAttachment internalLink = link(request, internalAttachment);
        when(attachments.findAllByInformationRequest_IdAndRoleOrderByAttachment_CreatedAtAsc(
                request.getId(), InformationAttachmentRole.REQUEST_CONTEXT))
                .thenReturn(List.of(publicLink, internalLink));
        when(attachmentReferenceService.downloadUrl(contextAttachment))
                .thenReturn("https://m2.example/api/attachments/7/content");

        var projection = service.findPending(ticketId);

        assertEquals("Adjunte una foto", projection.citizen().messageForCitizen());
        assertEquals("https://m2.example/api/attachments/7/content",
                projection.citizen().attachments().getFirst().getDownloadUrl());
        assertEquals(1, projection.citizen().attachments().size());
        verify(attachmentReferenceService, never()).downloadUrl(internalAttachment);
        assertEquals("Contexto privado", projection.staff().internalMessage());
        assertEquals("M6", projection.staff().requestedByModuleId());
        assertEquals(ActorType.EXTERNAL_USER, projection.staff().requestedByActorType());
        assertEquals(TicketStatus.IN_PROGRESS, projection.staff().resumeStatus());
    }

    @Test
    void noPendingRequestProducesNullProjection() {
        UUID ticketId = UUID.randomUUID();
        when(requests.findByTicketIdAndStatus(ticketId, InformationRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        assertNull(service.findPending(ticketId));
        verifyNoInteractions(attachments);
    }

    private InformationRequestAttachment link(InformationRequest request, Attachment attachment) {
        InformationRequestAttachment link = new InformationRequestAttachment();
        link.setInformationRequest(request);
        link.setAttachment(attachment);
        link.setRole(InformationAttachmentRole.REQUEST_CONTEXT);
        return link;
    }
}
