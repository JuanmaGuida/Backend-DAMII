package com.reclamos.backend.repository;

import com.reclamos.backend.entity.FormTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FormTemplateRepository extends JpaRepository<FormTemplate, Long> {
    Optional<FormTemplate> findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(Long requestTypeId);
}