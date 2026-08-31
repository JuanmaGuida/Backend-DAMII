package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.FormDefinitionResponse;
import com.reclamos.backend.dto.response.FormFieldResponse;
import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormService {
    private final RequestTypeRepository requestTypeRepository;
    private final FormTemplateRepository formTemplateRepository;
    private final FormFieldRepository formFieldRepository;

    public FormDefinitionResponse getFormForRequestType(Long requestTypeId) {
        RequestType requestType = requestTypeRepository.findById(requestTypeId)
                .filter(RequestType::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El Request Type solicitado no existe o está inactivo"));
        FormTemplate template = formTemplateRepository
                .findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestTypeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe un formulario configurado para el Request Type seleccionado"));

        List<FormFieldResponse> fields = formFieldRepository
                .findAllByFormTemplate_IdOrderByDisplayOrderAsc(template.getId())
                .stream().map(this::toResponse).toList();

        FormDefinitionResponse response = new FormDefinitionResponse();
        response.setRequestTypeId(requestType.getId());
        response.setRequestTypeCode(requestType.getCode());
        response.setVersion(template.getVersion());
        response.setFields(fields);
        return response;
    }

    private FormFieldResponse toResponse(FormField field) {
        Map<String, Object> publicConfig = field.getConfig() == null
                ? new HashMap<>() : new HashMap<>(field.getConfig());
        publicConfig.remove("risk");

        FormFieldResponse response = new FormFieldResponse();
        response.setCode(field.getCode());
        response.setLabel(field.getLabel());
        response.setType(field.getType());
        response.setRequired(field.getRequired());
        response.setDisplayOrder(field.getDisplayOrder());
        response.setConfig(publicConfig);
        return response;
    }
}