package com.reclamos.backend.repository;

import com.reclamos.backend.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long> {
}
