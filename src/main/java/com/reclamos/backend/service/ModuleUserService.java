package com.reclamos.backend.service;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.ModuleRole;
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

    public ModuleUser synchronize(ExternalIdentityProfile externalIdentity) {
        return moduleUserRepository.findByCitizenId(externalIdentity.citizenId())
                .map(user -> updateProfileIfChanged(user, externalIdentity))
                .orElseGet(() -> createOrFind(externalIdentity));
    }

    private ModuleUser createOrFind(ExternalIdentityProfile externalIdentity) {
        ModuleUser user = new ModuleUser();
        user.setCitizenId(externalIdentity.citizenId());
        copyProfile(user, externalIdentity);
        user.setRole(ModuleRole.CITIZEN);
        user.setAreaId(null);
        user.setActive(true);
        user.setLastSyncedAt(clock.instant());
        try {
            return moduleUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            return moduleUserRepository.findByCitizenId(externalIdentity.citizenId())
                    .map(existing -> updateProfileIfChanged(existing, externalIdentity))
                    .orElseThrow(() -> exception);
        }
    }

    private ModuleUser updateProfileIfChanged(ModuleUser user, ExternalIdentityProfile externalIdentity) {
        boolean changed = !Objects.equals(user.getFirstName(), externalIdentity.firstName())
                || !Objects.equals(user.getLastName(), externalIdentity.lastName())
                || !Objects.equals(user.getEmail(), externalIdentity.email())
                || !Objects.equals(user.getPhone(), externalIdentity.phone())
                || !Objects.equals(user.getProfileImageUrl(), externalIdentity.profileImageUrl());
        if (!changed) {
            return user;
        }
        copyProfile(user, externalIdentity);
        user.setLastSyncedAt(clock.instant());
        return moduleUserRepository.save(user);
    }

    private void copyProfile(ModuleUser user, ExternalIdentityProfile externalIdentity) {
        user.setFirstName(externalIdentity.firstName());
        user.setLastName(externalIdentity.lastName());
        user.setEmail(externalIdentity.email());
        user.setPhone(externalIdentity.phone());
        user.setProfileImageUrl(externalIdentity.profileImageUrl());
    }
}
