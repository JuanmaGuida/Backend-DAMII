package com.reclamos.backend.repository;

import com.reclamos.backend.entity.RiskRule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {
    @EntityGraph(attributePaths = {"formField", "formField.formTemplate"})
    List<RiskRule> findAllByFormField_FormTemplate_IdAndActiveTrue(Long formTemplateId);

    // BE - DDA2-133/134/135: el panel admin necesita ver TODAS las reglas de
    // una plantilla (activas e inactivas), a diferencia del cálculo de
    // riesgo (RiskCalculationService) que sólo usa las activas.
    @EntityGraph(attributePaths = {"formField"})
    List<RiskRule> findAllByFormField_FormTemplate_Id(Long formTemplateId);
}
