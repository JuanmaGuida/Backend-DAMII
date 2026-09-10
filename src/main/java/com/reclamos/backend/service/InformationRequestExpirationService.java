package com.reclamos.backend.service;

import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.CancellationReasonCode;
import com.reclamos.backend.entity.InformationRequest;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketCancellation;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.repository.InformationRequestRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketCancellationRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InformationRequestExpirationService {
    private static final String MODULE_ID = "M2";
    private static final String PUBLIC_MESSAGE =
            "El ticket fue cancelado por falta de respuesta dentro del plazo";
    private static final String INTERNAL_MESSAGE =
            "Vencimiento automático de solicitud de información";

    private final TicketRepository ticketRepository;
    private final InformationRequestRepository informationRequestRepository;
    private final TicketCancellationRepository cancellationRepository;
    private final TicketActivityRepository activityRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfDue(UUID requestId, UUID ticketId, Instant now) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId).orElse(null);
        if (ticket == null) return;

        InformationRequest request = informationRequestRepository
                .findByIdAndTicketIdForUpdate(requestId, ticketId)
                .orElse(null);
        if (request == null
                || request.getStatus() != InformationRequestStatus.PENDING
                || request.getDueAt().isAfter(now)
                || ticket.getCurrentStatus() != TicketStatus.PENDING_INFORMATION) {
            return;
        }

        request.setStatus(InformationRequestStatus.EXPIRED);
        informationRequestRepository.save(request);
        cancelForTimeout(ticket, now);
    }

    private void cancelForTimeout(Ticket ticket, Instant now) {
        if (cancellationRepository.existsByTicketId(ticket.getId())) return;

        ticket.setCurrentStatus(TicketStatus.CANCELLED);
        ticket.setStatusChangedAt(now);
        ticketRepository.save(ticket);

        TicketCancellation cancellation = new TicketCancellation();
        cancellation.setTicket(ticket);
        cancellation.setReasonCode(CancellationReasonCode.INFO_TIMEOUT);
        cancellation.setPublicMessage(PUBLIC_MESSAGE);
        cancellation.setInternalMessage(INTERNAL_MESSAGE);
        cancellation.setCancelledByType(ActorType.SYSTEM);
        cancellation.setCancelledById(null);
        cancellation.setCancelledByModuleId(MODULE_ID);
        cancellation.setCancelledAt(now);
        cancellationRepository.save(cancellation);

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.CANCELLED);
        activity.setPreviousStatus(TicketStatus.PENDING_INFORMATION);
        activity.setNewStatus(TicketStatus.CANCELLED);
        activity.setActorType(ActorType.SYSTEM);
        activity.setActorId(null);
        activity.setSourceModuleId(MODULE_ID);
        activity.setReasonCode(CancellationReasonCode.INFO_TIMEOUT.name());
        activity.setMessage(PUBLIC_MESSAGE);
        activity.setOccurredAt(now);
        activityRepository.save(activity);
    }
}
