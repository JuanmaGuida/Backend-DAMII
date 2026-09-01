package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Neighborhood;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NeighborhoodRepository extends JpaRepository<Neighborhood, UUID> {
}