package com.reclamos.backend.repository;

import com.reclamos.backend.entity.RiskRule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {
    @EntityGraph(attributePaths = {"formField", "formField.formTemplate"})
    List<RiskRule> findAllByFormField_FormTemplate_IdAndActiveTrue(Long formTemplateId);
}
