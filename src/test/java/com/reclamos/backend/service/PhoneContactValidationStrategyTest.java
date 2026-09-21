package com.reclamos.backend.service;

import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhoneContactValidationStrategyTest {
    private final PhoneContactValidationStrategy strategy = new PhoneContactValidationStrategy();

    @Test
    void supportsPhoneAndAcceptsValidValue() {
        assertEquals(AnonymousContactChannel.PHONE, strategy.channel());
        assertDoesNotThrow(() -> strategy.validate("+54 (11) 4444-5555"));
    }

    @Test
    void invalidCharactersPreserveExceptionAndMessage() {
        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> strategy.validate("+54 abc 123"));

        assertEquals("El teléfono de contacto no tiene un formato válido", exception.getMessage());
    }

    @Test
    void fewerThanSevenDigitsPreservesExceptionAndMessage() {
        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> strategy.validate("123456"));

        assertEquals("El teléfono de contacto debe contener entre 7 y 15 dígitos", exception.getMessage());
    }

    @Test
    void moreThanFifteenDigitsPreservesExceptionAndMessage() {
        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> strategy.validate("1234567890123456"));

        assertEquals("El teléfono de contacto debe contener entre 7 y 15 dígitos", exception.getMessage());
    }
}
