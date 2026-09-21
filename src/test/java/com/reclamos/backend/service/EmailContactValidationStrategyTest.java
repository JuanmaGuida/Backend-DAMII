package com.reclamos.backend.service;

import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailContactValidationStrategyTest {
    private final EmailContactValidationStrategy strategy = new EmailContactValidationStrategy(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void supportsEmailAndAcceptsValidValue() {
        assertEquals(AnonymousContactChannel.EMAIL, strategy.channel());
        assertDoesNotThrow(() -> strategy.validate("person@example.com"));
    }

    @Test
    void invalidEmailPreservesExceptionAndMessage() {
        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> strategy.validate("invalid"));

        assertEquals("El email de contacto no tiene un formato válido", exception.getMessage());
    }
}
