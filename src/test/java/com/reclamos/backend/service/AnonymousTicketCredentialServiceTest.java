package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidTicketRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AnonymousTicketCredentialServiceTest {
    private final AnonymousTicketCredentialService service =
            new AnonymousTicketCredentialService(new BCryptPasswordEncoder());

    @Test
    void providedPasswordIsValidatedAndOnlyItsBcryptHashIsReturned() {
        String password = " clave segura ";

        var material = service.prepare(password);

        assertNull(material.generatedPassword());
        assertNotEquals(password, material.passwordHash());
        assertTrue(material.passwordHash().startsWith("$2"));
        assertTrue(service.matches(password, material.passwordHash()));
        assertFalse(service.matches(password.trim(), material.passwordHash()));
        assertFalse(material.toString().contains(password));
    }

    @Test
    void omittedPasswordGeneratesIndependentUrlSafeThirtyTwoCharacterSecrets() {
        var first = service.prepare(null);
        var second = service.prepare(null);

        assertNotEquals(first.generatedPassword(), second.generatedPassword());
        assertTrue(first.generatedPassword().matches("[A-Za-z0-9_-]{32}"));
        assertEquals(24, java.util.Base64.getUrlDecoder().decode(first.generatedPassword()).length);
        assertTrue(service.matches(first.generatedPassword(), first.passwordHash()));
        assertFalse(service.matches(first.generatedPassword(), second.passwordHash()));
    }

    @Test
    void blankShortAndMoreThanSeventyTwoUtf8BytesAreRejected() {
        assertThrows(InvalidTicketRequestException.class, () -> service.prepare("        "));
        assertThrows(InvalidTicketRequestException.class, () -> service.prepare("1234567"));
        String tooManyBytes = "á".repeat(37);
        assertTrue(tooManyBytes.getBytes(StandardCharsets.UTF_8).length > 72);
        assertThrows(InvalidTicketRequestException.class, () -> service.prepare(tooManyBytes));
    }
}
