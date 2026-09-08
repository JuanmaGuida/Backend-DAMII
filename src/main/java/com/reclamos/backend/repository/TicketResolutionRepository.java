package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TicketResolutionRepository extends JpaRepository<TicketResolution, UUID> {
}