package com.reclamos.backend.repository;

import com.reclamos.backend.entity.ModuleUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModuleUserRepository extends JpaRepository<ModuleUser, Long> {
    Optional<ModuleUser> findByCitizenId(UUID citizenId);
}
