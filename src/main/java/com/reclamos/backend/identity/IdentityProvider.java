package com.reclamos.backend.identity;

import java.util.Optional;

/** Supplies validated external identity/profile only; local authorization belongs to M2. */
public interface IdentityProvider {
    Optional<ExternalAuthenticatedSession> authenticate(String username, String password);

    Optional<ExternalIdentityProfile> resolve(String token);
}
