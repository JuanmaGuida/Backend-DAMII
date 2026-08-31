package com.reclamos.backend.identity;

import java.util.Optional;

public interface IdentityProvider {
    Optional<AuthenticatedSession> authenticate(String username, String password);

    Optional<AuthenticatedIdentity> resolve(String token);
}
