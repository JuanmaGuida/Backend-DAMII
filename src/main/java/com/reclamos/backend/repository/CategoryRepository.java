package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByActiveTrueOrderByNameAsc();

    // BE - DDA2-114/116 (US "Panel de administración del catálogo"): el
    // panel admin necesita ver también las categorías inactivas para poder
    // reactivarlas o simplemente auditarlas, a diferencia del catálogo
    // público (findByActiveTrueOrderByNameAsc) que sólo expone las activas.
    List<Category> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id = :id")
    Optional<Category> findByIdForUpdate(@Param("id") Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
