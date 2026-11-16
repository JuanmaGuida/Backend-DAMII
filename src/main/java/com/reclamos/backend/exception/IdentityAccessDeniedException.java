package com.reclamos.backend.exception;

/** Valid external identity without usable local authorization, or a restricted M1 account. */
public class IdentityAccessDeniedException extends RuntimeException {
    public IdentityAccessDeniedException() {
        super("La cuenta no tiene acceso habilitado a M2");
    }
}
