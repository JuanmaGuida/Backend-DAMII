package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketActivity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketActivityRepository extends JpaRepository<TicketActivity, Long> {
    int countByTicketId(java.util.UUID ticketId);
}