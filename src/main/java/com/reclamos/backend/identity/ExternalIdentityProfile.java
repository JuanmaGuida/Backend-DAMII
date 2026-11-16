package com.reclamos.backend.identity;

import java.util.Objects;
import java.util.UUID;

public record ExternalIdentityProfile(
        String subjectId,
        UUID actorId,
        IdentityActorType actorType,
        IdentitySource identitySource,
        UUID citizenId,
        String firstName,
        String lastName,
        String displayName,
        String email,
        String phone,
        String profileImageUrl
) {
    public ExternalIdentityProfile {
        Objects.requireNonNull(subjectId, "subjectId is required");
        Objects.requireNonNull(actorType, "actorType is required").validate(actorId, citizenId);
        Objects.requireNonNull(identitySource, "identitySource is required");
    }

    /** Legacy local mock fixtures. Real providers must supply the full external identity. */
    public ExternalIdentityProfile(String subjectId, UUID citizenId, String firstName, String lastName,
                                   String displayName, String email, String phone, String profileImageUrl) {
        this(subjectId, citizenId, IdentityActorType.CIUDADANO, IdentitySource.MOCK, citizenId,
                firstName, lastName, displayName, email, phone, profileImageUrl);
    }
}
