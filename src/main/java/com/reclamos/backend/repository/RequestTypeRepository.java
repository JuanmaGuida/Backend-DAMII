package com.reclamos.backend.repository;

import com.reclamos.backend.entity.RequestType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long> {
    List<RequestType> findBySubcategory_IdAndActiveTrueOrderByNameAsc(Long subcategoryId);

    // BE - DDA2-114/116: listado admin bajo una Subcategory, incluye inactivos.
    List<RequestType> findBySubcategory_IdOrderByNameAsc(Long subcategoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RequestType rt where rt.id = :id")
    Optional<RequestType> findByIdForUpdate(@Param("id") Long id);

    @Query("select rt.subcategory.id from RequestType rt where rt.id = :id")
    Optional<Long> findSubcategoryIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RequestType rt where rt.subcategory.id = :subcategoryId order by rt.id")
    List<RequestType> findBySubcategoryIdOrderByIdAscForUpdate(@Param("subcategoryId") Long subcategoryId);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    boolean existsBySubcategory_IdAndNameIgnoreCase(Long subcategoryId, String name);

    boolean existsBySubcategory_IdAndNameIgnoreCaseAndIdNot(Long subcategoryId, String name, Long id);
}
