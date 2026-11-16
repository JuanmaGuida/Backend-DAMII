package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnonymousContactValidatorTest {
    private final EmailContactValidationStrategy emailStrategy = new EmailContactValidationStrategy(
            Validation.buildDefaultValidatorFactory().getValidator());
    private final PhoneContactValidationStrategy phoneStrategy = new PhoneContactValidationStrategy();
    private final AnonymousContactValidator validator = validatorWith(emailStrategy, phoneStrategy);

    @Test
    void absentOrEmptyContactIsValid() {
        assertNull(validator.validate(null));
        assertNull(validator.validate(new CreateTicketRequest.AnonymousContact(null, null)));
    }

    @Test
    void emailSelectsOnlyEmailStrategy() {
        ContactValidationStrategy email = strategy(AnonymousContactChannel.EMAIL);
        ContactValidationStrategy phone = strategy(AnonymousContactChannel.PHONE);
        AnonymousContactValidator selectingValidator = validatorWith(email, phone);

        var result = selectingValidator.validate(contact(AnonymousContactChannel.EMAIL, " person@example.com "));

        assertEquals("person@example.com", result.value());
        verify(email).validate("person@example.com");
        verify(phone, never()).validate(anyString());
    }

    @Test
    void phoneSelectsOnlyPhoneStrategyAndPreservesInternalFormatting() {
        ContactValidationStrategy email = strategy(AnonymousContactChannel.EMAIL);
        ContactValidationStrategy phone = strategy(AnonymousContactChannel.PHONE);
        AnonymousContactValidator selectingValidator = validatorWith(email, phone);

        var result = selectingValidator.validate(contact(AnonymousContactChannel.PHONE, " +54 (11) 4444-5555 "));

        assertEquals("+54 (11) 4444-5555", result.value());
        verify(phone).validate("+54 (11) 4444-5555");
        verify(email, never()).validate(anyString());
    }

    @Test
    void channelAndValueMustBeProvidedTogether() {
        InvalidTicketRequestException missingChannel = assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(new CreateTicketRequest.AnonymousContact(null, "value")));
        InvalidTicketRequestException missingValue = assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(new CreateTicketRequest.AnonymousContact(AnonymousContactChannel.EMAIL, null)));

        assertEquals("El canal y el valor de contacto deben informarse juntos", missingChannel.getMessage());
        assertEquals("El canal y el valor de contacto deben informarse juntos", missingValue.getMessage());
    }

    @Test
    void valueCannotBeEmptyAfterTrim() {
        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(contact(AnonymousContactChannel.EMAIL, "   ")));

        assertEquals("El valor de contacto no puede estar vacío", exception.getMessage());
    }

    @Test
    void contactLengthMatchesThe254CharacterPersistenceContract() {
        String email254 = "a".repeat(64) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(57) + ".com";

        assertEquals(254, email254.length());
        assertEquals(email254, validator.validate(contact(AnonymousContactChannel.EMAIL, email254)).value());
        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> validator.validate(contact(AnonymousContactChannel.EMAIL, "x" + email254)));
        assertEquals("El valor de contacto es demasiado largo", exception.getMessage());
    }

    @Test
    void missingStrategyFailsExplicitly() {
        AnonymousContactValidator validatorWithoutPhone = validatorWith(emailStrategy);

        InvalidTicketRequestException exception = assertThrows(InvalidTicketRequestException.class,
                () -> validatorWithoutPhone.validate(contact(AnonymousContactChannel.PHONE, "1234567")));

        assertEquals("No hay una estrategia de validación para el canal de contacto PHONE", exception.getMessage());
    }

    @Test
    void duplicateStrategyForChannelFailsAtConstruction() {
        ContactValidationStrategy first = strategy(AnonymousContactChannel.EMAIL);
        ContactValidationStrategy duplicate = strategy(AnonymousContactChannel.EMAIL);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> validatorWith(first, duplicate));

        assertEquals("Hay más de una estrategia de validación para el canal EMAIL", exception.getMessage());
    }

    @Test
    void requestContactToStringRedactsChannelAndValue() {
        var contact = contact(AnonymousContactChannel.EMAIL, "private@example.test");

        assertFalse(contact.toString().contains("EMAIL"));
        assertFalse(contact.toString().contains("private@example.test"));
    }

    private ContactValidationStrategy strategy(AnonymousContactChannel channel) {
        ContactValidationStrategy strategy = mock(ContactValidationStrategy.class);
        when(strategy.channel()).thenReturn(channel);
        return strategy;
    }

    private AnonymousContactValidator validatorWith(ContactValidationStrategy... strategies) {
        return new AnonymousContactValidator(List.of(strategies));
    }

    private CreateTicketRequest.AnonymousContact contact(AnonymousContactChannel channel, String value) {
        return new CreateTicketRequest.AnonymousContact(channel, value);
    }
}
