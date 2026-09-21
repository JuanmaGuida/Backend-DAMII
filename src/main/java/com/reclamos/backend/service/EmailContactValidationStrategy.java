package com.reclamos.backend.service;

import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailContactValidationStrategy implements ContactValidationStrategy {
    private final Validator validator;

    @Override
    public AnonymousContactChannel channel() {
        return AnonymousContactChannel.EMAIL;
    }

    @Override
    public void validate(String value) {
        if (!validator.validate(new EmailValue(value)).isEmpty()) {
            throw new InvalidTicketRequestException("El email de contacto no tiene un formato válido");
        }
    }

    private record EmailValue(@Email String value) {
    }
}
