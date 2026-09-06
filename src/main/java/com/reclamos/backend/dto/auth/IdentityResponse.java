package com.reclamos.backend.dto.auth;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;

import java.util.UUID;

public record IdentityResponse(
        String subjectId,
        UUID citizenId,
        String displayName,
        String areaId,
        ModuleRole role
) {
    public static IdentityResponse from(AuthenticatedIdentity identity) {
        return new IdentityResponse(
                identity.subjectId(),
                identity.citizenId(),
                identity.displayName(),
                identity.areaId(),
                identity.role()
        );
    }
}
