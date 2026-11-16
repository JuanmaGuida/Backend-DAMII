package com.reclamos.backend.service;

import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import org.springframework.stereotype.Component;

@Component
public class PhoneContactValidationStrategy implements ContactValidationStrategy {
    @Override
    public AnonymousContactChannel channel() {
        return AnonymousContactChannel.PHONE;
    }

    @Override
    public void validate(String value) {
        if (!value.matches("[+0-9()\\-\\s]+")) {
            throw new InvalidTicketRequestException("El teléfono de contacto no tiene un formato válido");
        }
        String digitsWithOptionalPlus = value.replaceAll("[\\s()\\-]", "");
        if (!digitsWithOptionalPlus.matches("\\+?\\d{7,15}")) {
            throw new InvalidTicketRequestException("El teléfono de contacto debe contener entre 7 y 15 dígitos");
        }
    }
}
