package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.TrackingTicketNotFoundException;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TrackingService {
    private final TicketRepository ticketRepository;
    private final TrackingCodeService trackingCodeService;
    private final TicketSlaService ticketSlaService;
    private final InformationRequestProjectionService informationRequestProjectionService;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentReferenceService attachmentReferenceService;

    @Transactional(readOnly = true)
    public TrackingTicketResponse findByTrackingCode(String trackingCode) {
        if (!trackingCodeService.isValid(trackingCode)) {
            throw new TrackingTicketNotFoundException();
        }
        String trackingCodeHash = trackingCodeService.hash(trackingCode);
        Ticket ticket = ticketRepository.findByTrackingCodeHash(trackingCodeHash)
                .orElseThrow(TrackingTicketNotFoundException::new);
        return toPublicResponse(ticket);
    }

    private TrackingTicketResponse toPublicResponse(Ticket ticket) {
        RequestType requestType = ticket.getRequestType();
        Subcategory subcategory = requestType.getSubcategory();
        Category category = subcategory.getCategory();
        TicketSlaService.DeadlineSnapshot deadlines = ticketSlaService.findDeadlineSnapshot(ticket);
        return new TrackingTicketResponse(
                ticket.getPublicId(),
                ticket.getCurrentStatus(),
                ticket.getSummary(),
                ticket.getDescription(),
                ticket.getCreatedAt(),
                ticket.getStatusChangedAt(),
                new TrackingTicketResponse.RequestTypeSummary(requestType.getName()),
                new TrackingTicketResponse.CategorySummary(category.getName()),
                new TrackingTicketResponse.SubcategorySummary(subcategory.getName()),
                new TrackingTicketResponse.SlaSummary(
                        deadlines.firstResponseDueAt(),
                        deadlines.resolutionDueAt()),
                publicAttachments(ticket),
                projection(ticket)
        );
    }

    /**
     * Antes de esto, TrackingTicketResponse no incluía attachments (ni
     * description): el propietario anónimo perdía la galería de adjuntos al
     * volver a consultar su ticket por /tracking/access, aunque el archivo
     * existiera. Sólo PUBLIC — nunca INTERNAL, que puede existir en un ticket
     * anónimo si lo sube staff — mismo criterio que TicketService para la
     * vista ciudadana. El downloadUrl que trae cada TicketAttachmentResponse
     * apunta al GET autenticado (igual que en cualquier otra respuesta); un
     * propietario anónimo no tiene Bearer token, así que para descargar debe
     * usar el id acá expuesto contra POST /tracking/actions/attachments/
     * {attachmentId}/content con trackingCode+password, no seguir esa URL
     * directamente.
     */
    private List<TicketAttachmentResponse> publicAttachments(Ticket ticket) {
        return attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                        ticket.getId(), MessageVisibility.PUBLIC).stream()
                .map(this::toAttachmentResponse)
                .toList();
    }

    private TicketAttachmentResponse toAttachmentResponse(Attachment attachment) {
        return new TicketAttachmentResponse(attachment.getId(), attachment.getFileName(),
                attachment.getContentType(), attachment.getSizeBytes(), attachment.getVisibility(),
                attachment.getCreatedAt(), attachmentReferenceService.downloadUrl(attachment));
    }

    private com.reclamos.backend.dto.response.PendingInformationRequestResponse projection(Ticket ticket) {
        InformationRequestProjectionService.Projection pending =
                informationRequestProjectionService.findPending(ticket.getId());
        return pending == null ? null : pending.citizen();
    }
}
