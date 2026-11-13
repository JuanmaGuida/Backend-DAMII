package com.reclamos.backend.service;

import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.ExternalAttachmentReferenceRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExternalAttachmentService {
    private final ExternalAttachmentReferenceRepository references;
    private final TicketRepository tickets;

    public void validate(List<UpdateTicketStatusRequest.ExternalAttachment> attachments) {
        for (var item : attachments) {
            try {
                URI uri = URI.create(item.url());
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null
                        || item.fileName() == null || item.fileName().isBlank()
                        || item.fileName().chars().anyMatch(Character::isISOControl)
                        || item.contentType() == null
                        || !item.contentType().matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
                        || (item.sizeBytes() != null && item.sizeBytes() < 0)) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw new InvalidTicketRequestException("Referencia de evidencia externa inválida");
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Ticket ticket, UUID eventId, String source, UpdateTicketStatusRequest request) {
        for (int i = 0; i < request.attachments().size(); i++) {
            var item = request.attachments().get(i);
            var reference = new ExternalAttachmentReference();
            reference.setTicket(ticket); reference.setExternalEventId(eventId); reference.setOrdinal(i);
            reference.setUpdateType(request.updateType()); reference.setSourceModuleId(source);
            reference.setOccurredAt(request.updateOccurredAt()); reference.setFileName(item.fileName());
            reference.setContentType(item.contentType()); reference.setUrl(item.url()); reference.setSizeBytes(item.sizeBytes());
            // The shared schema has no visibility field. Never infer PUBLIC from a public message.
            reference.setVisibility(MessageVisibility.INTERNAL);
            references.save(reference);
        }
    }

    @Transactional(readOnly = true)
    public List<Reference> list(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        boolean staff = identity != null && (identity.role() == ModuleRole.AGENT || identity.role() == ModuleRole.ADMIN
                || (identity.role() == ModuleRole.AREA_RESPONSIBLE
                && Objects.equals(ticket.getResponsibleAreaId(), identity.areaId())));
        boolean owner = identity != null && !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(identity.citizenId());
        if (!staff && !owner) throw new UnauthorizedTicketOperationException();
        return references.findAllByTicket_IdOrderByOccurredAtAscIdAsc(ticketId).stream()
                .filter(ref -> staff || ref.getVisibility() == MessageVisibility.PUBLIC)
                .map(ref -> new Reference(ref.getId(), ref.getExternalEventId(), ref.getUpdateType(), ref.getOccurredAt(),
                        ref.getFileName(), ref.getContentType(), ref.getUrl(), ref.getSizeBytes(), ref.getVisibility())).toList();
    }

    public record Reference(Long id, UUID eventId, UpdateTicketStatusType updateType, Instant occurredAt,
                            String fileName, String contentType, String url, Long sizeBytes, MessageVisibility visibility) { }
}
