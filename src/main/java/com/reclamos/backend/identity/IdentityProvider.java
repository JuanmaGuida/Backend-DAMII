package com.reclamos.backend.identity;

import java.util.Optional;

public interface IdentityProvider {
    Optional<ExternalAuthenticatedSession> authenticate(String username, String password);

    Optional<ExternalIdentityProfile> resolve(String token);
}
