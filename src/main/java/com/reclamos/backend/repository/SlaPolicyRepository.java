package com.reclamos.backend.repository;

import com.reclamos.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {
    Optional<SlaPolicy> findByPriorityAndSlaType(Priority priority, SlaType slaType);
    Optional<SlaPolicy> findByTicketTypeAndSlaType(TicketType ticketType, SlaType slaType);
}