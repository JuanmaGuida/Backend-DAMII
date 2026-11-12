package com.reclamos.backend.identity;

import java.util.Objects;
import java.util.UUID;

/** External identity type from M1; independent of the local ModuleRole. */
public enum IdentityActorType {
    CIUDADANO,
    EMPLEADO;

    public void validate(UUID actorId, UUID citizenId) {
        Objects.requireNonNull(actorId, "actorId is required");
        if (this == CIUDADANO && !actorId.equals(citizenId)) {
            throw new IllegalArgumentException("A citizen must have actorId equal to citizenId");
        }
        if (this == EMPLEADO && citizenId != null) {
            throw new IllegalArgumentException("An employee identity must not invent a citizenId");
        }
    }
}
