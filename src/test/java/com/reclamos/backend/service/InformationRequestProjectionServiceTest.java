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
    private final InformationRequestProjectionService service =
            new InformationRequestProjectionService(requests, attachments);

    @Test
    void pendingProjectionSeparatesCitizenFieldsFromStaffContext() {
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
        when(attachments.findAllByInformationRequest_IdAndRoleOrderByAttachment_CreatedAtAsc(
                request.getId(), InformationAttachmentRole.REQUEST_CONTEXT)).thenReturn(List.of());

        var projection = service.findPending(ticketId);

        assertEquals("Adjunte una foto", projection.citizen().messageForCitizen());
        assertEquals(List.of(), projection.citizen().attachments());
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
}
