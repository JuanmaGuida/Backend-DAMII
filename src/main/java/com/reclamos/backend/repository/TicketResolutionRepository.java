package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TicketResolutionRepository extends JpaRepository<TicketResolution, UUID> {
    @Query("select max(r.resolutionNumber) from TicketResolution r where r.ticket.id = :ticketId")
    Optional<Integer> findMaxResolutionNumber(@Param("ticketId") UUID ticketId);
}
