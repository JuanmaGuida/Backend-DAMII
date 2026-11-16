package com.reclamos.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AnonymousTicketCredentialsRequest(
        @NotBlank(message = "El código de seguimiento es obligatorio") String trackingCode,
        @NotBlank(message = "La contraseña anónima es obligatoria") String anonymousAccessPassword
) {
    @Override
    public String toString() {
        return "AnonymousTicketCredentialsRequest[trackingCode=<redacted>, anonymousAccessPassword=<redacted>]";
    }
}
