package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketLocationRepository extends JpaRepository<TicketLocation, Long> {

    Optional<TicketLocation> findByTicket_Id(UUID ticketId);

    List<TicketLocation> findAllByTicket_IdIn(Collection<UUID> ticketIds);
}
