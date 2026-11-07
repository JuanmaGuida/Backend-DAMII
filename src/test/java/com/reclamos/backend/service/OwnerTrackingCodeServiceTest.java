package com.reclamos.backend.service;

import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class OwnerTrackingCodeServiceTest {
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TrackingCodeService codes = new TrackingCodeService();
    private final TrackingCodeCipher cipher = new TrackingCodeCipher(TrackingCodeCipherTest.TEST_KEY, "test-v1", codes);
    private final OwnerTrackingCodeService service = new OwnerTrackingCodeService(tickets, cipher);
    private final UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
    private final String code = codes.generate();

    @ParameterizedTest @EnumSource(ModuleRole.class)
    void allRolesRecoverOnlyTheirOwnCodeWithNoRepositoryWrites(ModuleRole role, CapturedOutput output) {
        Ticket ticket = ticket();
        when(tickets.findByIdAndCitizenIdAndAnonymousFalse(id, owner)).thenReturn(Optional.of(ticket));
        var response = service.get(id, identity(owner, role));
        assertThat(response.trackingCode()).isEqualTo(code);
        assertThat(response.toString()).doesNotContain(code);
        verify(tickets).findByIdAndCitizenIdAndAnonymousFalse(id, owner);
        verifyNoMoreInteractions(tickets);
        assertThat(output).contains("outcome=success").doesNotContain(code, ticket.getTrackingCodeHash(),
                java.util.Base64.getEncoder().encodeToString(ticket.getTrackingCodeCiphertext()));
    }

    @ParameterizedTest @EnumSource(ModuleRole.class)
    void noRoleBypassesTheOwnerScopedQuery(ModuleRole role) {
        UUID stranger = UUID.randomUUID();
        when(tickets.findByIdAndCitizenIdAndAnonymousFalse(id, stranger)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id, identity(stranger, role)))
                .isInstanceOf(OwnerTrackingCodeNotFoundException.class);
        verify(tickets).findByIdAndCitizenIdAndAnonymousFalse(id, stranger);
        verifyNoMoreInteractions(tickets);
    }

    @Test
    void missingIdentityNeverQueriesTheDatabase() {
        assertThatThrownBy(() -> service.get(id, null)).isInstanceOf(InvalidAuthenticationException.class);
        verifyNoInteractions(tickets);
    }

    @Test
    void historicalAndPartialMaterialAreDifferentFailures(CapturedOutput output) {
        Ticket historical = new Ticket();
        when(tickets.findByIdAndCitizenIdAndAnonymousFalse(id, owner)).thenReturn(Optional.of(historical));
        assertThatThrownBy(() -> service.get(id, identity(owner, ModuleRole.CITIZEN)))
                .isInstanceOf(TrackingCodeNotRecoverableException.class);
        historical.setTrackingCodeKeyId("test-v1");
        assertThatThrownBy(() -> service.get(id, identity(owner, ModuleRole.CITIZEN)))
                .isInstanceOf(TrackingCodeUnavailableException.class);
        assertThat(output).contains("outcome=not_recoverable", "outcome=unavailable").doesNotContain(code);
    }

    private Ticket ticket() {
        Ticket ticket = new Ticket(); ticket.setId(id); ticket.setCitizenId(owner);
        ticket.setTrackingCodeHash(codes.hash(code));
        var material = cipher.encrypt(id, owner, code);
        ticket.setTrackingCodeCiphertext(material.ciphertext()); ticket.setTrackingCodeNonce(material.nonce());
        ticket.setTrackingCodeKeyId(material.keyId()); return ticket;
    }

    private AuthenticatedIdentity identity(UUID citizenId, ModuleRole role) {
        return new AuthenticatedIdentity(citizenId.toString(), citizenId, "Test", null, role);
    }
}
