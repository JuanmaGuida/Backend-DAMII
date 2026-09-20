package com.reclamos.backend.service;

import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.InvalidAnonymousTicketCredentialsException;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnonymousTicketAccessService {
    private final TicketRepository ticketRepository;
    private final TrackingCodeService trackingCodeService;
    private final AnonymousTicketCredentialService credentialService;

    @Transactional(readOnly = true)
    public AnonymousTicketAccess authenticate(String trackingCode, String password) {
        if (!trackingCodeService.isValid(trackingCode)) {
            throw new InvalidAnonymousTicketCredentialsException();
        }
        Ticket ticket = ticketRepository.findByTrackingCodeHash(trackingCodeService.hash(trackingCode))
                .orElseThrow(InvalidAnonymousTicketCredentialsException::new);
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null
                || ticket.getAnonymousAccessPasswordHash() == null
                || !credentialService.matches(password, ticket.getAnonymousAccessPasswordHash())) {
            throw new InvalidAnonymousTicketCredentialsException();
        }
        return new AnonymousTicketAccess(ticket.getId());
    }

    public record AnonymousTicketAccess(UUID ticketId) {
    }
}
