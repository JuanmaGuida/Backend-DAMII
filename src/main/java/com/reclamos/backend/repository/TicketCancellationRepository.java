package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TicketCancellationRepository extends JpaRepository<TicketCancellation, UUID> {
    boolean existsByTicketId(UUID ticketId);
}