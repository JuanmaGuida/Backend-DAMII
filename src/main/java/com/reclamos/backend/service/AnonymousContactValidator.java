package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnonymousContactValidator {
    private static final int MAX_CONTACT_LENGTH = 254;

    private final Validator validator;

    public ValidatedContact validate(CreateTicketRequest.AnonymousContact contact) {
        if (contact == null || (contact.channel() == null && contact.value() == null)) {
            return null;
        }
        if (contact.channel() == null || contact.value() == null) {
            throw new InvalidTicketRequestException("El canal y el valor de contacto deben informarse juntos");
        }

        String value = contact.value().trim();
        if (value.isEmpty()) {
            throw new InvalidTicketRequestException("El valor de contacto no puede estar vacío");
        }
        if (value.length() > MAX_CONTACT_LENGTH) {
            throw new InvalidTicketRequestException("El valor de contacto es demasiado largo");
        }

        if (contact.channel() == AnonymousContactChannel.EMAIL) {
            if (!validator.validate(new EmailValue(value)).isEmpty()) {
                throw new InvalidTicketRequestException("El email de contacto no tiene un formato válido");
            }
        } else {
            validatePhone(value);
        }
        return new ValidatedContact(contact.channel(), value);
    }

    private void validatePhone(String value) {
        if (!value.matches("[+0-9()\\-\\s]+")) {
            throw new InvalidTicketRequestException("El teléfono de contacto no tiene un formato válido");
        }
        String digitsWithOptionalPlus = value.replaceAll("[\\s()\\-]", "");
        if (!digitsWithOptionalPlus.matches("\\+?\\d{7,15}")) {
            throw new InvalidTicketRequestException("El teléfono de contacto debe contener entre 7 y 15 dígitos");
        }
    }

    private record EmailValue(@Email String value) {
    }

    public record ValidatedContact(AnonymousContactChannel channel, String value) {
    }
}
