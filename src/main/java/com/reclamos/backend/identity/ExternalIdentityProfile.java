package com.reclamos.backend.identity;

import java.util.Objects;
import java.util.UUID;

public record ExternalIdentityProfile(
        String subjectId,
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
        Objects.requireNonNull(citizenId, "citizenId is required");
        requireText(firstName, "firstName");
        requireText(lastName, "lastName");
        requireText(displayName, "displayName");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
