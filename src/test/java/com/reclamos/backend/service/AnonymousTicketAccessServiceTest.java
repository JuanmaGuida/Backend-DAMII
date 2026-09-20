package com.reclamos.backend.service;

import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.InvalidAnonymousTicketCredentialsException;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AnonymousTicketAccessServiceTest {
    private static final String CODE = "0123456789abcdefghijklmnopqrstuv";
    private static final String HASH = "tracking-hash";

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TrackingCodeService trackingCodes = mock(TrackingCodeService.class);
    private final AnonymousTicketCredentialService credentials = mock(AnonymousTicketCredentialService.class);
    private final AnonymousTicketAccessService service =
            new AnonymousTicketAccessService(tickets, trackingCodes, credentials);

    @Test
    void authenticatesAnonymousOwnerAndReturnsOnlyTicketId() {
        Ticket ticket = anonymousTicket();
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));
        when(credentials.matches("correct-password", "bcrypt-hash")).thenReturn(true);

        var access = service.authenticate(CODE, "correct-password");

        assertEquals(ticket.getId(), access.ticketId());
    }

    @Test
    void invalidTrackingWrongPasswordAndIdentifiedTicketUseTheSameError() {
        assertThrows(InvalidAnonymousTicketCredentialsException.class,
                () -> service.authenticate("invalid", "password"));
        verifyNoInteractions(tickets, credentials);

        reset(tickets, credentials);
        Ticket anonymous = anonymousTicket();
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(anonymous));
        when(credentials.matches("wrong-password", "bcrypt-hash")).thenReturn(false);
        assertThrows(InvalidAnonymousTicketCredentialsException.class,
                () -> service.authenticate(CODE, "wrong-password"));

        Ticket identified = new Ticket();
        identified.setId(UUID.randomUUID());
        identified.setCitizenId(UUID.randomUUID());
        identified.setAnonymous(false);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(identified));
        assertThrows(InvalidAnonymousTicketCredentialsException.class,
                () -> service.authenticate(CODE, "any-password"));
        verify(credentials, never()).matches("any-password", null);
    }

    @Test
    void unknownTrackingAndMissingHashAreRejected() {
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.empty());
        assertThrows(InvalidAnonymousTicketCredentialsException.class,
                () -> service.authenticate(CODE, "password"));

        Ticket ticket = anonymousTicket();
        ticket.setAnonymousAccessPasswordHash(null);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));
        assertThrows(InvalidAnonymousTicketCredentialsException.class,
                () -> service.authenticate(CODE, "password"));
        verifyNoInteractions(credentials);
    }

    private Ticket anonymousTicket() {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setAnonymous(true);
        ticket.setCitizenId(null);
        ticket.setAnonymousAccessPasswordHash("bcrypt-hash");
        return ticket;
    }
}
