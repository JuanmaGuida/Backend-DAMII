package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AnonymousContactValidator {
    private static final int MAX_CONTACT_LENGTH = 254;

    private final Map<AnonymousContactChannel, ContactValidationStrategy> strategies;

    public AnonymousContactValidator(List<ContactValidationStrategy> strategies) {
        this.strategies = new EnumMap<>(AnonymousContactChannel.class);
        for (ContactValidationStrategy strategy : strategies) {
            ContactValidationStrategy existing = this.strategies.putIfAbsent(strategy.channel(), strategy);
            if (existing != null) {
                throw new IllegalStateException(
                        "Hay más de una estrategia de validación para el canal " + strategy.channel());
            }
        }
    }

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

        ContactValidationStrategy strategy = strategies.get(contact.channel());
        if (strategy == null) {
            throw new InvalidTicketRequestException(
                    "No hay una estrategia de validación para el canal de contacto " + contact.channel());
        }
        strategy.validate(value);
        return new ValidatedContact(contact.channel(), value);
    }

    public record ValidatedContact(AnonymousContactChannel channel, String value) {
    }
}
