package com.reclamos.backend.repository;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.IdentitySource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ModuleUserRepository extends JpaRepository<ModuleUser, Long> {
    Optional<ModuleUser> findByActorId(UUID actorId);

    Optional<ModuleUser> findByCitizenId(UUID citizenId);

    @Transactional
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:id AS text), 492))", nativeQuery = true)
    Integer lockActorIdentity(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ModuleUser u where u.actorId=:id")
    Optional<ModuleUser> findByActorIdForUpdate(@Param("id") UUID id);

    /** Update only M1-owned profile fields, never a detached copy of local authorization. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ModuleUser u set u.firstName=coalesce(:firstName,u.firstName), "
            + "u.lastName=coalesce(:lastName,u.lastName), u.email=coalesce(:email,u.email), "
            + "u.phone=coalesce(:phone,u.phone), u.profileImageUrl=coalesce(:image,u.profileImageUrl), "
                + "u.lastSyncedAt=:syncedAt, u.updatedAt=:syncedAt, "
                + "u.externalProfileUpdatedAt=case when u.identitySource=com.reclamos.backend.identity.IdentitySource.M1 "
                + "then :syncedAt else u.externalProfileUpdatedAt end "
            + "where u.actorId=:actorId and u.identitySource=:source")
    int updateExternalProfile(@Param("actorId") UUID actorId, @Param("source") IdentitySource source,
                              @Param("firstName") String firstName, @Param("lastName") String lastName,
                              @Param("email") String email, @Param("phone") String phone,
                              @Param("image") String image, @Param("syncedAt") Instant syncedAt);
}
