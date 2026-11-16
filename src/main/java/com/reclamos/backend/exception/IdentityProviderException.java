package com.reclamos.backend.exception;

/** Sanitized upstream failure: never retain response bodies, credentials or token-bearing causes. */
public class IdentityProviderException extends RuntimeException {
    private final boolean unavailable;

    public IdentityProviderException(boolean unavailable) {
        super(unavailable ? "El servicio de identidad no está disponible. Intente nuevamente."
                : "El servicio de identidad devolvió una respuesta no válida");
        this.unavailable = unavailable;
    }

    public boolean isUnavailable() { return unavailable; }
}
