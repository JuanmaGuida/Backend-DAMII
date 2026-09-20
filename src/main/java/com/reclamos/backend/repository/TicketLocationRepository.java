package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketLocationRepository extends JpaRepository<TicketLocation, Long> {

    Optional<TicketLocation> findByTicket_Id(UUID ticketId);

    List<TicketLocation> findAllByTicket_IdIn(Collection<UUID> ticketIds);

    @Query("""
            select location
            from TicketLocation location
            join fetch location.ticket ticket
            join fetch ticket.requestType requestType
            join fetch requestType.subcategory subcategory
            join fetch subcategory.category category
            left join fetch location.neighborhood neighborhood
            where ticket.id <> :sourceTicketId
              and category.id = :categoryId
              and ticket.createdAt between :createdFrom and :createdTo
              and ticket.currentStatus in :statuses
              and location.latitude is not null
              and location.longitude is not null
            """)
    List<TicketLocation> findDuplicateCandidates(
            @Param("sourceTicketId") UUID sourceTicketId,
            @Param("categoryId") Long categoryId,
            @Param("createdFrom") Instant createdFrom,
            @Param("createdTo") Instant createdTo,
            @Param("statuses") Collection<TicketStatus> statuses);
}
