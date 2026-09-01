package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormValidationService {
    private final FormTemplateRepository formTemplateRepository;
    private final FormFieldRepository formFieldRepository;

    public void validate(RequestType requestType, Map<String, Object> formData) {
        validateAndGetFields(requestType, formData);
    }

    public List<FormField> validateAndGetFields(RequestType requestType, Map<String, Object> formData) {
        if (requestType == null || requestType.getId() == null) {
            throw new FormValidationException("El Request Type es obligatorio");
        }
        if (!requestType.isActive()) {
            throw new FormValidationException("El Request Type seleccionado está inactivo");
        }
        FormTemplate template = formTemplateRepository
                .findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestType.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe un formulario configurado para el Request Type seleccionado"));
        List<FormField> fields = formFieldRepository
                .findAllByFormTemplate_IdOrderByDisplayOrderAsc(template.getId());
        Map<String, Object> data = formData == null ? Collections.emptyMap() : formData;
        Set<String> allowedCodes = fields.stream().map(FormField::getCode).collect(Collectors.toSet());

        data.keySet().stream().filter(code -> !allowedCodes.contains(code)).findFirst()
                .ifPresent(code -> {
                    throw new FormValidationException(
                            "El campo '" + code + "' no pertenece al formulario seleccionado");
                });

        for (FormField field : fields) {
            Object value = data.get(field.getCode());
            if (Boolean.TRUE.equals(field.getRequired()) && (value == null || isBlankText(field, value))) {
                throw new FormValidationException("El campo '" + field.getLabel() + "' es obligatorio");
            }
            if (value != null) {
                validateValue(field, value);
            }
        }
        return List.copyOf(fields);
    }

    private boolean isBlankText(FormField field, Object value) {
        return (field.getType() == com.reclamos.backend.entity.FormFieldType.TEXT
                || field.getType() == com.reclamos.backend.entity.FormFieldType.TEXTAREA)
                && value instanceof String text && text.isBlank();
    }

    private void validateValue(FormField field, Object value) {
        switch (field.getType()) {
            case TEXT, TEXTAREA -> requireType(field, value instanceof String);
            case BOOLEAN -> requireType(field, value instanceof Boolean);
            case NUMBER -> requireType(field, value instanceof Number);
            case SELECT -> validateSelect(field, value);
            case DATE -> validateDate(field, value);
        }
    }

    private void requireType(FormField field, boolean valid) {
        if (!valid) {
            throw new FormValidationException("El campo '" + field.getCode()
                    + "' debe ser de tipo " + field.getType());
        }
    }

    private void validateSelect(FormField field, Object value) {
        requireType(field, value instanceof String);
        Object configuredOptions = field.getConfig() == null ? null : field.getConfig().get("options");
        boolean valid = configuredOptions instanceof List<?> options && options.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(option -> value.equals(option.get("value")));
        if (!valid) {
            throw new FormValidationException(
                    "El campo '" + field.getLabel() + "' contiene una opción inválida");
        }
    }

    private void validateDate(FormField field, Object value) {
        requireType(field, value instanceof String);
        try {
            LocalDate.parse((String) value);
        } catch (DateTimeParseException exception) {
            throw new FormValidationException(
                    "El campo '" + field.getCode() + "' debe tener formato yyyy-MM-dd");
        }
    }
}