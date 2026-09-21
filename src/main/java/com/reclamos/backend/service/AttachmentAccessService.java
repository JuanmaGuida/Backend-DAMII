package com.reclamos.backend.service;

import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.AttachmentStorageUnavailableException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.storage.FileStorage;
import com.reclamos.backend.storage.StoredContent;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentAccessService {
    private final AttachmentRepository attachmentRepository;
    private final FileStorage fileStorage;

    @Transactional(readOnly = true)
    public Download download(Long attachmentId, AuthenticatedIdentity identity) {
        Attachment attachment = find(attachmentId);
        if (!canDownload(attachment, identity)) {
            throw new UnauthorizedTicketOperationException();
        }
        return open(attachment);
    }

    @Transactional(readOnly = true)
    public Download downloadAnonymous(Long attachmentId, UUID authenticatedTicketId) {
        Attachment attachment = find(attachmentId);
        if (!attachment.getTicket().getId().equals(authenticatedTicketId)
                || attachment.getVisibility() != MessageVisibility.PUBLIC) {
            throw new ResourceNotFoundException("El adjunto solicitado no existe");
        }
        return open(attachment);
    }

    private Attachment find(Long attachmentId) {
        if (attachmentId == null) {
            throw new ResourceNotFoundException("El adjunto solicitado no existe");
        }
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("El adjunto solicitado no existe"));
    }

    private boolean canDownload(Attachment attachment, AuthenticatedIdentity identity) {
        if (identity == null) {
            return false;
        }
        Ticket ticket = attachment.getTicket();
        boolean owner = !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(identity.citizenId());
        if (attachment.getVisibility() == MessageVisibility.PUBLIC && owner) {
            return true;
        }
        if (identity.role() == ModuleRole.AGENT || identity.role() == ModuleRole.ADMIN) {
            return true;
        }
        return identity.role() == ModuleRole.AREA_RESPONSIBLE
                && Objects.equals(ticket.getResponsibleAreaId(), identity.areaId());
    }

    private Download open(Attachment attachment) {
        StoredContent content = fileStorage.read(attachment.getStorageKey());
        if (content.sizeBytes() != attachment.getSizeBytes()) {
            throw new AttachmentStorageUnavailableException();
        }
        return new Download(content.resource(), attachment.getFileName(), attachment.getContentType(),
                content.sizeBytes());
    }

    public record Download(Resource resource, String fileName, String contentType, long sizeBytes) {
    }
}
