package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.exception.InvalidAuthenticationException;
import com.reclamos.backend.repository.ModuleUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ModuleUserService {
    private final ModuleUserRepository moduleUserRepository;
    private final Clock clock;

    @org.springframework.transaction.annotation.Transactional
    public ModuleUser synchronize(ExternalIdentityProfile externalIdentity) {
        // Also serialize MOCK first logins, since creation now shares the synchronization transaction.
        moduleUserRepository.lockActorIdentity(externalIdentity.actorId());
        return moduleUserRepository.findByActorId(externalIdentity.actorId())
                .map(user -> updateProfileIfChanged(user, externalIdentity))
                .orElseGet(() -> createOrFind(externalIdentity));
    }

    private ModuleUser createOrFind(ExternalIdentityProfile externalIdentity) {
        // Staff must be provisioned locally with its exact actorId/type/source and role.
        // No external role/claim, email or numeric subject can grant staff privileges.
        if (externalIdentity.actorType() == IdentityActorType.EMPLEADO
                || missing(externalIdentity.firstName()) || missing(externalIdentity.lastName())) {
            throw new InvalidAuthenticationException();
        }
        ModuleUser user = new ModuleUser();
        user.setActorId(externalIdentity.actorId());
        user.setActorType(externalIdentity.actorType());
        user.setIdentitySource(externalIdentity.identitySource());
        user.setCitizenId(externalIdentity.citizenId());
        copyProfile(user, externalIdentity);
        user.setRole(ModuleRole.CITIZEN);
        user.setAreaId(null);
        user.setActive(true);
        user.setLastSyncedAt(clock.instant());
        if (externalIdentity.identitySource() == com.reclamos.backend.identity.IdentitySource.M1) {
            user.setExternalProfileUpdatedAt(clock.instant());
        }
        try {
            return moduleUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            return moduleUserRepository.findByActorId(externalIdentity.actorId())
                    .map(existing -> updateProfileIfChanged(existing, externalIdentity))
                    .orElseThrow(InvalidAuthenticationException::new);
        }
    }

    private ModuleUser updateProfileIfChanged(ModuleUser user, ExternalIdentityProfile externalIdentity) {
        if (!Objects.equals(user.getActorId(), externalIdentity.actorId())
                || user.getActorType() != externalIdentity.actorType()
                || user.getIdentitySource() != externalIdentity.identitySource()
                || !Objects.equals(user.getCitizenId(), externalIdentity.citizenId())
                || (user.getActorType() == IdentityActorType.EMPLEADO
                    && (user.getRole() == null || user.getRole() == ModuleRole.CITIZEN))) {
            throw new InvalidAuthenticationException();
        }
        boolean changed = changed(user.getFirstName(), externalIdentity.firstName())
                || changed(user.getLastName(), externalIdentity.lastName())
                || changed(user.getEmail(), externalIdentity.email())
                || changed(user.getPhone(), externalIdentity.phone())
                || changed(user.getProfileImageUrl(), externalIdentity.profileImageUrl());
        boolean m1ProfileObservation = externalIdentity.identitySource() == com.reclamos.backend.identity.IdentitySource.M1
                && (!missing(externalIdentity.firstName()) || !missing(externalIdentity.lastName())
                    || !missing(externalIdentity.email()) || !missing(externalIdentity.phone()) || !missing(externalIdentity.profileImageUrl()));
        if (!changed && !m1ProfileObservation) {
            return user;
        }
        moduleUserRepository.updateExternalProfile(user.getActorId(), user.getIdentitySource(),
                supplied(externalIdentity.firstName()), supplied(externalIdentity.lastName()),
                supplied(externalIdentity.email()), supplied(externalIdentity.phone()),
                supplied(externalIdentity.profileImageUrl()), clock.instant());
        return moduleUserRepository.findByActorId(user.getActorId())
                .orElseThrow(InvalidAuthenticationException::new);
    }

    private void copyProfile(ModuleUser user, ExternalIdentityProfile externalIdentity) {
        user.setFirstName(externalIdentity.firstName());
        user.setLastName(externalIdentity.lastName());
        user.setEmail(externalIdentity.email());
        user.setPhone(externalIdentity.phone());
        user.setProfileImageUrl(externalIdentity.profileImageUrl());
    }

    private static boolean changed(String current, String incoming) {
        return !missing(incoming) && !Objects.equals(current, incoming);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static String supplied(String value) {
        return missing(value) ? null : value;
    }
}
