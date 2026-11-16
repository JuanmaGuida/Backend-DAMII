package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        resolveAndValidate(requestType, formData);
    }

    /**
     * Only answers belonging to the ticket's confirmed template version may enter ROUTED.
     * Do not resolve the currently active version or serialize field/config/risk metadata.
     * Final confirmation validates required answers before this snapshot is published.
     */
    public Map<String, Object> snapshotAnswers(Ticket ticket) {
        Map<String, Object> answers = ticket.getFormData();
        if (answers == null || answers.isEmpty() || ticket.getFormTemplateId() == null) {
            return Map.of();
        }
        if (!formTemplateRepository.existsByIdAndRequestType_Id(
                ticket.getFormTemplateId(), ticket.getRequestType().getId())) {
            throw new FormValidationException("El formulario no corresponde a la clasificación confirmada");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (FormField field : formFieldRepository
                .findAllByFormTemplate_IdOrderByDisplayOrderAsc(ticket.getFormTemplateId())) {
            if (!answers.containsKey(field.getCode())) {
                continue;
            }
            Object value = answers.get(field.getCode());
            if (value == null) {
                if (!field.isAllowUnknown()) {
                    throw new FormValidationException("El campo '" + field.getCode()
                            + "' no admite un valor desconocido");
                }
            } else {
                validateValue(field, value);
            }
            snapshot.put(field.getCode(), value);
        }
        return snapshot;
    }

    public ResolvedForm resolveAndValidate(RequestType requestType, Map<String, Object> formData) {
        if (requestType == null || requestType.getId() == null) {
            throw new FormValidationException("El Request Type es obligatorio");
        }
        if (!requestType.isActive()) {
            throw new FormValidationException("El Request Type seleccionado está inactivo");
        }
        Map<String, Object> data = formData == null ? Collections.emptyMap() : formData;
        FormTemplate template = formTemplateRepository
                .findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestType.getId())
                .orElse(null);
        if (template == null) {
            if (!data.isEmpty()) {
                throw new FormValidationException(
                        "El Request Type seleccionado no posee un formulario para las respuestas informadas");
            }
            return new ResolvedForm(null, List.of(), data);
        }
        return validateTemplate(template, data, true);
    }

    public ResolvedForm resolveVersion(RequestType type, Long templateId, Map<String, Object> data, boolean complete) {
        // Partial inspection remains available if an administrator disables the proposed type.
        // Creation checks activity under its type lock; answers/confirmation require complete validation.
        if (type == null || complete && !type.isActive()) {
            throw new FormValidationException("El Request Type seleccionado está inactivo");
        }
        FormTemplate template = templateId == null ? null : formTemplateRepository.findById(templateId)
                .filter(t -> t.getRequestType().getId().equals(type.getId()))
                .orElseThrow(() -> new FormValidationException("La versión de formulario no corresponde al tipo"));
        if (template == null) {
            if (data != null && !data.isEmpty()) throw new FormValidationException("El tipo no posee formulario");
            return new ResolvedForm(null, List.of(), Map.of());
        }
        return validateTemplate(template, data == null ? Map.of() : data, complete);
    }

    private ResolvedForm validateTemplate(FormTemplate template, Map<String, Object> data, boolean complete) {
        List<FormField> fields = formFieldRepository.findAllByFormTemplate_IdOrderByDisplayOrderAsc(template.getId());
        Set<String> allowedCodes = fields.stream().map(FormField::getCode).collect(Collectors.toSet());
        for (String code : data.keySet()) {
            if (!allowedCodes.contains(code)) throw new FormValidationException(
                    "El campo '" + code + "' no pertenece al formulario seleccionado");
        }
        for (FormField field : fields) {
            if (!data.containsKey(field.getCode())) {
                if (complete && field.isRequired()) throw new FormValidationException(
                        "El campo '" + field.getCode() + "' es obligatorio");
                continue;
            }
            Object value = data.get(field.getCode());
            if (value == null) {
                if (!field.isAllowUnknown()) throw new FormValidationException(
                        "El campo '" + field.getCode() + "' no admite un valor desconocido");
            } else {
                validateValue(field, value);
            }
        }
        return new ResolvedForm(template, fields, data);
    }

    /** Pins the active target version at proposal creation; subsequent validation uses that exact id. */
    public FormTemplate resolveActiveTemplate(RequestType requestType) {
        if (requestType == null || requestType.getId() == null) {
            throw new FormValidationException("El Request Type es obligatorio");
        }
        if (!requestType.isActive()) {
            throw new FormValidationException("El Request Type seleccionado está inactivo");
        }
        return formTemplateRepository
                .findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestType.getId())
                .orElse(null);
    }

    private void validateValue(FormField field, Object value) {
        switch (field.getType()) {
            case TEXT, TEXTAREA -> requireType(field, value instanceof String);
            case BOOLEAN -> requireType(field, value instanceof Boolean);
            case NUMBER -> validateNumber(field, value);
            case SELECT -> validateSelect(field, value);
            case DATE -> validateDate(field, value);
        }
    }

    private void validateNumber(FormField field, Object value) {
        requireType(field, value instanceof Number);
        java.math.BigDecimal number;
        try { number = new java.math.BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new FormValidationException("El número debe ser finito"); }
        Map<String, Object> config = field.getConfig() == null ? Map.of() : field.getConfig();
        for (String bound : List.of("min", "max")) {
            Object configured = config.get(bound);
            if (configured instanceof Number limit) {
                int comparison = number.compareTo(new java.math.BigDecimal(limit.toString()));
                if (bound.equals("min") && comparison < 0 || bound.equals("max") && comparison > 0) {
                    throw new FormValidationException("El campo '" + field.getCode() + "' está fuera de rango");
                }
            }
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
