package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.TrackingCodeResponse;
import com.reclamos.backend.exception.InvalidAuthenticationException;
import com.reclamos.backend.exception.OwnerTrackingCodeNotFoundException;
import com.reclamos.backend.exception.TrackingCodeNotRecoverableException;
import com.reclamos.backend.exception.TrackingCodeUnavailableException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OwnerTrackingCodeService {
    private static final Logger AUDIT = LoggerFactory.getLogger("security.tracking-code");
    private final TicketRepository tickets;
    private final TrackingCodeCipher cipher;

    @Transactional(readOnly = true)
    public TrackingCodeResponse get(UUID ticketId, AuthenticatedIdentity identity) {
        if (identity == null) throw new InvalidAuthenticationException();
        var ticket = tickets.findByIdAndCitizenIdAndAnonymousFalse(ticketId, identity.citizenId())
                .orElseThrow(() -> {
                    audit(identity, ticketId, "denied");
                    return new OwnerTrackingCodeNotFoundException();
                });
        if (ticket.getTrackingCodeCiphertext() == null && ticket.getTrackingCodeNonce() == null
                && ticket.getTrackingCodeKeyId() == null) {
            audit(identity, ticketId, "not_recoverable");
            throw new TrackingCodeNotRecoverableException();
        }
        try {
            String code = cipher.decrypt(ticket.getId(), ticket.getCitizenId(), ticket.getTrackingCodeHash(),
                    new TrackingCodeCipher.EncryptedCode(ticket.getTrackingCodeCiphertext(),
                            ticket.getTrackingCodeNonce(), ticket.getTrackingCodeKeyId()));
            audit(identity, ticketId, "success");
            return new TrackingCodeResponse(code);
        } catch (TrackingCodeUnavailableException exception) {
            audit(identity, ticketId, "unavailable");
            throw exception;
        }
    }

    private void audit(AuthenticatedIdentity identity, UUID ticketId, String outcome) {
        AUDIT.info("operation=recover_tracking_code actor={} ticket={} outcome={}",
                identity.citizenId(), ticketId, outcome);
    }
}
