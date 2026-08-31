package com.reclamos.backend.repository;

import com.reclamos.backend.entity.FormField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormFieldRepository extends JpaRepository<FormField, Long> {
    List<FormField> findAllByFormTemplate_IdOrderByDisplayOrderAsc(Long formTemplateId);
}