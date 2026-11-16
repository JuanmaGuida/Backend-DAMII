package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AnonymousTicketActionRequest<T>(
        @NotBlank(message = "El código de seguimiento es obligatorio") String trackingCode,
        @NotBlank(message = "La contraseña anónima es obligatoria") String anonymousAccessPassword,
        @NotNull T payload
) {
    @Override
    public String toString() {
        return "AnonymousTicketActionRequest[trackingCode=<redacted>, anonymousAccessPassword=<redacted>, "
                + "payload=" + payload + "]";
    }
}
