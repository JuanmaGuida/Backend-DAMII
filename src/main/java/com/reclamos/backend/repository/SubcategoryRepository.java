package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Subcategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubcategoryRepository extends JpaRepository<Subcategory, Long> {
    List<Subcategory> findByCategory_IdAndActiveTrueOrderByNameAsc(Long categoryId);
}
