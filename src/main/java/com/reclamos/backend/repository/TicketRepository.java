package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Ticket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    boolean existsByTrackingCodeHash(String trackingCodeHash);

    boolean existsByPublicId(String publicId);

    @EntityGraph(attributePaths = {"requestType.subcategory.category"})
    Optional<Ticket> findByTrackingCodeHash(String trackingCodeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") UUID id);

    @Query("select t.id from Ticket t where t.currentStatus = com.reclamos.backend.entity.TicketStatus.RESOLVED " +
            "and t.resolutionConfirmationDueAt is not null and t.resolutionConfirmationDueAt <= :now")
    List<UUID> findExpiredResolutionConfirmationIds(@Param("now") Instant now);
}
