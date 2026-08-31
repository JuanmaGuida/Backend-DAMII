package com.reclamos.backend.identity;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AuthenticatedIdentity(
        String subjectId,
        UUID citizenId,
        String displayName,
        String areaId,
        Set<ModuleRole> roles
) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(subjectId, "subjectId is required");
        Objects.requireNonNull(citizenId, "citizenId is required");
        Objects.requireNonNull(displayName, "displayName is required");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles are required"));
    }
}
