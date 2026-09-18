package com.reclamos.backend.repository;

import com.reclamos.backend.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long> {
    List<RequestType> findBySubcategory_IdAndActiveTrueOrderByNameAsc(Long subcategoryId);

    // BE - DDA2-114/116: listado admin bajo una Subcategory, incluye inactivos.
    List<RequestType> findBySubcategory_IdOrderByNameAsc(Long subcategoryId);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    boolean existsBySubcategory_IdAndNameIgnoreCase(Long subcategoryId, String name);

    boolean existsBySubcategory_IdAndNameIgnoreCaseAndIdNot(Long subcategoryId, String name, Long id);
}
