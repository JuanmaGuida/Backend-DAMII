package com.reclamos.backend.identity;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedIdentity(
        String subjectId,
        UUID citizenId,
        String displayName,
        String areaId,
        ModuleRole role
) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(subjectId, "subjectId is required");
        Objects.requireNonNull(citizenId, "citizenId is required");
        Objects.requireNonNull(displayName, "displayName is required");
        Objects.requireNonNull(role, "role is required");
    }
}
