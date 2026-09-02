package com.reclamos.backend.repository;

import com.reclamos.backend.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long> {
    List<RequestType> findBySubcategory_IdAndActiveTrueOrderByNameAsc(Long subcategoryId);
}
