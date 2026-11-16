package com.reclamos.backend.service;

import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.InvalidAnonymousTicketCredentialsException;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.security.TrackingAttemptLimiter;
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
    private final TrackingAttemptLimiter attemptLimiter;

    @Transactional(readOnly = true)
    public AnonymousTicketAccess authenticate(String trackingCode, String password) {
        String trackingCodeHash = trackingCodeService.isValid(trackingCode)
                ? trackingCodeService.hash(trackingCode)
                : null;
        // El bloqueo se evalúa antes de las credenciales para no revelar si eran correctas.
        attemptLimiter.assertAllowed(trackingCodeHash);
        Ticket ticket = trackingCodeHash == null
                ? null
                : ticketRepository.findByTrackingCodeHash(trackingCodeHash).orElse(null);
        if (ticket == null) {
            throw rejected(null);
        }
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null
                || ticket.getAnonymousAccessPasswordHash() == null
                || !credentialService.matches(password, ticket.getAnonymousAccessPasswordHash())) {
            throw rejected(trackingCodeHash);
        }
        return new AnonymousTicketAccess(ticket.getId());
    }

    /**
     * Registra el fallo y devuelve el error uniforme. Sólo se asocia el hash
     * al ticket cuando existe, para que códigos inventados no generen estado.
     */
    private InvalidAnonymousTicketCredentialsException rejected(String existingTicketHash) {
        attemptLimiter.recordFailure(existingTicketHash);
        return new InvalidAnonymousTicketCredentialsException();
    }

    public record AnonymousTicketAccess(UUID ticketId) {
    }
}
