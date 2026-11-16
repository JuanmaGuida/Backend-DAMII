package com.reclamos.backend.repository;

import com.reclamos.backend.entity.FormTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FormTemplateRepository extends JpaRepository<FormTemplate, Long> {
    Optional<FormTemplate> findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(Long requestTypeId);

    boolean existsByIdAndRequestType_Id(Long id, Long requestTypeId);

    // BE - DDA2-133/134/135: próxima versión a asignar al versionar un
    // formulario — busca la más alta que exista, activa o no, para no
    // repetir números aunque alguna versión anterior haya quedado inactiva.
    Optional<FormTemplate> findFirstByRequestType_IdOrderByVersionDesc(Long requestTypeId);
}
