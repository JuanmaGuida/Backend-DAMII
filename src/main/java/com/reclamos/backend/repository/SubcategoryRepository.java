package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Subcategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubcategoryRepository extends JpaRepository<Subcategory, Long> {
    List<Subcategory> findByCategory_IdAndActiveTrueOrderByNameAsc(Long categoryId);

    // BE - DDA2-114/116: listado admin bajo una Category, incluye inactivas.
    List<Subcategory> findByCategory_IdOrderByNameAsc(Long categoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subcategory s where s.id = :id")
    Optional<Subcategory> findByIdForUpdate(@Param("id") Long id);

    @Query("select s.category.id from Subcategory s where s.id = :id")
    Optional<Long> findCategoryIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subcategory s where s.category.id = :categoryId order by s.id")
    List<Subcategory> findByCategoryIdOrderByIdAscForUpdate(@Param("categoryId") Long categoryId);

    boolean existsByCategory_IdAndNameIgnoreCase(Long categoryId, String name);

    boolean existsByCategory_IdAndNameIgnoreCaseAndIdNot(Long categoryId, String name, Long id);
}
