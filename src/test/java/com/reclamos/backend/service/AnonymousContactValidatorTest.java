package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnonymousContactValidatorTest {
    private final AnonymousContactValidator validator = new AnonymousContactValidator(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void absentOrEmptyContactIsValid() {
        assertNull(validator.validate(null));
        assertNull(validator.validate(new CreateTicketRequest.AnonymousContact(null, null)));
    }

    @Test
    void validEmailAndPhoneAreTrimmedWithoutAggressiveNormalization() {
        var email = validator.validate(contact(AnonymousContactChannel.EMAIL, " person@example.com "));
        var phone = validator.validate(contact(AnonymousContactChannel.PHONE, " +54 (11) 4444-5555 "));

        assertEquals("person@example.com", email.value());
        assertEquals("+54 (11) 4444-5555", phone.value());
    }

    @Test
    void incompleteInvalidEmailAndInvalidPhoneAreRejected() {
        assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(new CreateTicketRequest.AnonymousContact(null, "value")));
        assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(new CreateTicketRequest.AnonymousContact(AnonymousContactChannel.EMAIL, null)));
        assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(contact(AnonymousContactChannel.EMAIL, "invalid")));
        assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(contact(AnonymousContactChannel.PHONE, "+54 abc 123")));
        assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(contact(AnonymousContactChannel.PHONE, "123456")));
        assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(contact(AnonymousContactChannel.PHONE, "1234567890123456")));
    }

    private CreateTicketRequest.AnonymousContact contact(AnonymousContactChannel channel, String value) {
        return new CreateTicketRequest.AnonymousContact(channel, value);
    }
}
