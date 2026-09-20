package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.SatisfactionSurveyRequest;
import com.reclamos.backend.dto.response.SatisfactionSurveyResponse;
import com.reclamos.backend.entity.SatisfactionSurvey;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.SatisfactionSurveyConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.SatisfactionSurveyRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SatisfactionSurveyService {
    private final TicketRepository ticketRepository;
    private final SatisfactionSurveyRepository surveyRepository;
    private final Clock clock;

    @Transactional
    public SatisfactionSurveyResponse create(UUID ticketId, SatisfactionSurveyRequest request,
                                             AuthenticatedIdentity identity) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
        requireOwner(ticket, identity);
        if (ticket.getCurrentStatus() != TicketStatus.CLOSED) {
            throw new SatisfactionSurveyConflictException(
                    "La encuesta sólo puede registrarse cuando el ticket está cerrado");
        }
        if (surveyRepository.existsByTicket_Id(ticketId)) {
            throw new SatisfactionSurveyConflictException(
                    "El ticket ya posee una encuesta de satisfacción registrada");
        }

        SatisfactionSurvey survey = new SatisfactionSurvey();
        survey.setTicket(ticket);
        survey.setScore(request.getScore());
        survey.setComment(request.getComment());
        survey.setCreatedAt(clock.instant());
        survey = surveyRepository.save(survey);
        return new SatisfactionSurveyResponse(survey.getId(), ticket.getId(), survey.getScore(),
                survey.getComment(), survey.getCreatedAt());
    }

    private void requireOwner(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null || ticket.isAnonymous() || ticket.getCitizenId() == null
                || !ticket.getCitizenId().equals(identity.citizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
    }
}