package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Ticket;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @EntityGraph(attributePaths = {"requestType.subcategory.category"})
    Optional<Ticket> findByTrackingCodeHash(String trackingCodeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") UUID id);

    /**
     * GET /me/tickets (Entidades V1.49 §"Autenticación, roles y vistas"):
     * listado propio del ciudadano, sin scoping por rol ni área — a
     * diferencia de la bandeja staff de la Story 3.1 (ver TicketSpecifications).
     */
    Page<Ticket> findByCitizenId(UUID citizenId, Pageable pageable);

    @Query("select t.id from Ticket t where t.currentStatus = com.reclamos.backend.entity.TicketStatus.RESOLVED " +
            "and t.resolutionConfirmationDueAt is not null and t.resolutionConfirmationDueAt <= :now")
    List<UUID> findExpiredResolutionConfirmationIds(@Param("now") Instant now);
}
