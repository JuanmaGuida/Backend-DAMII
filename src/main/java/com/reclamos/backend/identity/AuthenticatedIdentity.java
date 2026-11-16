package com.reclamos.backend.identity;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedIdentity(
        String subjectId,
        UUID actorId,
        IdentityActorType actorType,
        UUID citizenId,
        String displayName,
        String areaId,
        ModuleRole role
) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(subjectId, "subjectId is required");
        Objects.requireNonNull(actorType, "actorType is required").validate(actorId, citizenId);
        Objects.requireNonNull(displayName, "displayName is required");
        Objects.requireNonNull(role, "role is required");
        if (actorType == IdentityActorType.EMPLEADO && role == ModuleRole.CITIZEN) {
            throw new IllegalArgumentException("Employees require a locally provisioned staff role");
        }
    }

    /** Compatibility for the existing synthetic citizen fixtures. */
    public AuthenticatedIdentity(String subjectId, UUID citizenId, String displayName,
                                 String areaId, ModuleRole role) {
        this(subjectId, citizenId, IdentityActorType.CIUDADANO, citizenId, displayName, areaId, role);
    }

    public boolean hasCitizenIdentity() {
        return citizenId != null;
    }

    public boolean ownsCitizenId(UUID ownerId) {
        return citizenId != null && citizenId.equals(ownerId);
    }
}
