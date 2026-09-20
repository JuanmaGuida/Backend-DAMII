package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Label;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    boolean existsByCodeIgnoreCase(String code);
    List<Label> findAllByOrderByNameAscIdAsc();
}