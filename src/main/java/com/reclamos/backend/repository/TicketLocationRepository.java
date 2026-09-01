package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketLocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketLocationRepository extends JpaRepository<TicketLocation, Long> {
}