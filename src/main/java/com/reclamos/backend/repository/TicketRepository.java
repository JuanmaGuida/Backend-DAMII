package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    boolean existsByTrackingCodeHash(String trackingCodeHash);
    boolean existsByPublicId(String publicId);
}
