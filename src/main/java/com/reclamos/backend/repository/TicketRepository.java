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

    @Query("select t.id from Ticket t where t.mainTicket is null and t.currentStatus in :statuses " +
            "and t.id > :after and t.id <= :upper order by t.id")
    List<UUID> findPriorityCandidates(@Param("statuses") java.util.Collection<com.reclamos.backend.entity.TicketStatus> statuses,
            @Param("after") UUID after, @Param("upper") UUID upper, Pageable pageable);

    @Query("select t.id from Ticket t order by t.id desc")
    List<UUID> findPriorityScanUpperBound(Pageable pageable);

    boolean existsByTrackingCodeHash(String trackingCodeHash);

    boolean existsByRequestType_Id(Long requestTypeId);

    boolean existsByMainTicket_Id(UUID mainTicketId);

    boolean existsByMainTicket_IdAndCurrentStatus(UUID mainTicketId,
            com.reclamos.backend.entity.TicketStatus status);

    @Query("select t.id from Ticket t where t.mainTicket.id = :id and t.currentStatus = " +
            "com.reclamos.backend.entity.TicketStatus.DUPLICATE order by t.id")
    List<UUID> findActiveDuplicateIds(@Param("id") UUID id);

    // The main is already locked. NOWAIT avoids waiting for a link transaction
    // that holds a child first and is itself waiting for the main.
    @Query(value = "select * from tickets where main_ticket_id = :id and current_status = 'DUPLICATE' " +
            "order by id for update nowait", nativeQuery = true)
    List<Ticket> lockCancellationDuplicates(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"requestType.subcategory.category"})
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findWithClassificationById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"requestType.subcategory.category"})
    Optional<Ticket> findByTrackingCodeHash(String trackingCodeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.mainTicket.id = :mainTicketId and t.currentStatus = " +
            "com.reclamos.backend.entity.TicketStatus.DUPLICATE")
    List<Ticket> findActiveDuplicatesForUpdate(@Param("mainTicketId") UUID mainTicketId);

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
