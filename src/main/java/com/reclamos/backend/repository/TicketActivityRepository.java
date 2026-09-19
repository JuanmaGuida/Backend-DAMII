package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketActivityRepository extends JpaRepository<TicketActivity, Long> {
    int countByTicketId(UUID ticketId);

    List<TicketActivity> findAllByTicket_IdOrderBySequenceAsc(UUID ticketId);
}
