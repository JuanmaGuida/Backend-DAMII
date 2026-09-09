package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.AuthenticatedSession;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.IdentityProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final IdentityProvider identityProvider;
    private final ModuleUserService moduleUserService;

    public Optional<AuthenticatedSession> authenticate(String username, String password) {
        return identityProvider.authenticate(username, password)
                .flatMap(session -> effectiveIdentity(session.profile())
                        .map(identity -> new AuthenticatedSession(
                                session.token(), session.expiresAt(), identity)));
    }

    public Optional<AuthenticatedIdentity> resolve(String token) {
        return identityProvider.resolve(token).flatMap(this::effectiveIdentity);
    }

    private Optional<AuthenticatedIdentity> effectiveIdentity(ExternalIdentityProfile externalIdentity) {
        ModuleUser moduleUser = moduleUserService.synchronize(externalIdentity);
        if (!moduleUser.isActive()) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedIdentity(
                externalIdentity.subjectId(),
                externalIdentity.citizenId(),
                externalIdentity.displayName(),
                moduleUser.getAreaId(),
                moduleUser.getRole()
        ));
    }
}
