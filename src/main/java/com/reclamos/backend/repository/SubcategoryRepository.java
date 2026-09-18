package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Subcategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubcategoryRepository extends JpaRepository<Subcategory, Long> {
    List<Subcategory> findByCategory_IdAndActiveTrueOrderByNameAsc(Long categoryId);

    // BE - DDA2-114/116: listado admin bajo una Category, incluye inactivas.
    List<Subcategory> findByCategory_IdOrderByNameAsc(Long categoryId);

    boolean existsByCategory_IdAndNameIgnoreCase(Long categoryId, String name);

    boolean existsByCategory_IdAndNameIgnoreCaseAndIdNot(Long categoryId, String name, Long id);
}
