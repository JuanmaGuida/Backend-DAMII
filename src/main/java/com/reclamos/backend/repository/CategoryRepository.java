package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByActiveTrueOrderByNameAsc();

    // BE - DDA2-114/116 (US "Panel de administración del catálogo"): el
    // panel admin necesita ver también las categorías inactivas para poder
    // reactivarlas o simplemente auditarlas, a diferencia del catálogo
    // público (findByActiveTrueOrderByNameAsc) que sólo expone las activas.
    List<Category> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
