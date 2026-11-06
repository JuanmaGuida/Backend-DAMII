package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AttachmentUploadRequest;
import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketAttachmentUploadService {
    private final TicketRepository ticketRepository;
    private final AttachmentService attachmentService;
    private final AttachmentReferenceService attachmentReferenceService;
    private final Clock clock;

    @Transactional
    public List<TicketAttachmentResponse> upload(UUID ticketId, AttachmentUploadRequest request,
                                                 MultipartFile[] files, AuthenticatedIdentity identity) {
        Ticket ticket = findTicket(ticketId);
        UploadAuthorization authorization = authorize(ticket, request.visibility(), identity);
        return store(ticket, authorization.actor(), request.visibility(), files);
    }

    @Transactional
    public List<TicketAttachmentResponse> uploadAnonymous(UUID ticketId, AttachmentUploadRequest request,
                                                          MultipartFile[] files) {
        Ticket ticket = findTicket(ticketId);
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null
                || request.visibility() != MessageVisibility.PUBLIC) {
            throw new UnauthorizedTicketOperationException();
        }
        return store(ticket, new AttachmentService.UploadActor(ActorType.CITIZEN, null),
                MessageVisibility.PUBLIC, files);
    }

    private Ticket findTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
    }

    private UploadAuthorization authorize(Ticket ticket, MessageVisibility visibility,
                                          AuthenticatedIdentity identity) {
        if (identity == null) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean owner = !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(identity.citizenId());
        if (owner) {
            if (visibility != MessageVisibility.PUBLIC) {
                throw new UnauthorizedTicketOperationException();
            }
            return new UploadAuthorization(new AttachmentService.UploadActor(
                    ActorType.CITIZEN, identity.citizenId().toString()));
        }
        if (identity.role() == ModuleRole.CITIZEN) {
            throw new UnauthorizedTicketOperationException();
        }
        if (identity.role() == ModuleRole.AREA_RESPONSIBLE
                && !Objects.equals(ticket.getResponsibleAreaId(), identity.areaId())) {
            throw new UnauthorizedTicketOperationException();
        }
        ActorType actorType = switch (identity.role()) {
            case AGENT -> ActorType.AGENT;
            case ADMIN -> ActorType.ADMIN;
            case AREA_RESPONSIBLE -> ActorType.AREA_RESPONSIBLE;
            case CITIZEN -> throw new UnauthorizedTicketOperationException();
        };
        return new UploadAuthorization(new AttachmentService.UploadActor(
                actorType, identity.citizenId().toString()));
    }

    private List<TicketAttachmentResponse> store(Ticket ticket, AttachmentService.UploadActor actor,
                                                 MessageVisibility visibility, MultipartFile[] files) {
        List<AttachmentService.ValidatedAttachment> validated = attachmentService.validate(files);
        if (validated.isEmpty()) {
            throw new com.reclamos.backend.exception.InvalidAttachmentException(
                    "Debe incluir al menos un archivo.");
        }
        Instant createdAt = clock.instant();
        List<Attachment> stored = attachmentService.storeForTicket(
                ticket, actor, visibility, validated, createdAt);
        return stored.stream().map(this::toResponse).toList();
    }

    private TicketAttachmentResponse toResponse(Attachment attachment) {
        return new TicketAttachmentResponse(
                attachment.getId(), attachment.getFileName(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.getVisibility(), attachment.getCreatedAt(),
                attachmentReferenceService.downloadUrl(attachment));
    }

    private record UploadAuthorization(AttachmentService.UploadActor actor) {
    }
}
