package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.TrackingTicketNotFoundException;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TrackingService {
    private final TicketRepository ticketRepository;
    private final TrackingCodeService trackingCodeService;

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
        return new TrackingTicketResponse(
                ticket.getPublicId(),
                ticket.getCurrentStatus(),
                ticket.getSummary(),
                ticket.getCreatedAt(),
                ticket.getStatusChangedAt(),
                new TrackingTicketResponse.RequestTypeSummary(requestType.getName()),
                new TrackingTicketResponse.CategorySummary(category.getName()),
                new TrackingTicketResponse.SubcategorySummary(subcategory.getName()),
                new TrackingTicketResponse.SlaSummary(
                        ticket.getEffectiveFirstResponseDueAt(),
                        ticket.getEffectiveResolutionDueAt())
        );
    }
}