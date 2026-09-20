package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.PendingInformationRequestResponse;
import com.reclamos.backend.dto.response.StaffInformationRequestContextResponse;
import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.entity.InformationAttachmentRole;
import com.reclamos.backend.entity.InformationRequest;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.repository.InformationRequestAttachmentRepository;
import com.reclamos.backend.repository.InformationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InformationRequestProjectionService {
    private final InformationRequestRepository informationRequestRepository;
    private final InformationRequestAttachmentRepository informationRequestAttachmentRepository;
    private final AttachmentReferenceService attachmentReferenceService;

    @Transactional(readOnly = true)
    public Projection findPending(UUID ticketId) {
        return informationRequestRepository.findByTicketIdAndStatus(ticketId, InformationRequestStatus.PENDING)
                .map(this::toProjection)
                .orElse(null);
    }

    private Projection toProjection(InformationRequest request) {
        var attachments = informationRequestAttachmentRepository
                .findAllByInformationRequest_IdAndRoleOrderByAttachment_CreatedAtAsc(
                        request.getId(), InformationAttachmentRole.REQUEST_CONTEXT)
                .stream()
                .map(link -> {
                    var attachment = link.getAttachment();
                    return new TicketAttachmentResponse(attachment.getId(), attachment.getFileName(),
                            attachment.getContentType(), attachment.getSizeBytes(), attachment.getVisibility(),
                            attachment.getCreatedAt(), attachmentReferenceService.downloadUrl(attachment));
                })
                .toList();
        return new Projection(
                new PendingInformationRequestResponse(request.getStatus(), request.getMessageForCitizen(),
                        request.getRequestedAt(), request.getDueAt(), attachments),
                new StaffInformationRequestContextResponse(request.getInternalMessage(),
                        request.getRequestedByModuleId(), request.getRequestedByActorType(),
                        request.getResumeStatus()));
    }

    public record Projection(PendingInformationRequestResponse citizen,
                             StaffInformationRequestContextResponse staff) {
    }
}
