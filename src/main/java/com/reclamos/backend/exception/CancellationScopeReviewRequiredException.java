package com.reclamos.backend.exception;

/** A rejected external fact commits only the scope-review audit, never lifecycle effects. */
public class CancellationScopeReviewRequiredException extends TicketStateConflictException {
    public CancellationScopeReviewRequiredException() {
        super("El rechazo de un principal con duplicados requiere revisión administrativa de alcance");
    }
}
