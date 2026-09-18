package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.FormFieldAdminRequest;
import com.reclamos.backend.dto.request.FormTemplateAdminRequest;
import com.reclamos.backend.dto.request.RiskRuleAdminRequest;
import com.reclamos.backend.dto.response.FormFieldAdminResponse;
import com.reclamos.backend.dto.response.FormTemplateAdminResponse;
import com.reclamos.backend.dto.response.RiskRuleAdminResponse;
import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.RiskOperator;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.exception.InvalidCatalogRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.RiskRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.groupingBy;

/**
 * BE - DDA2-133/134/135 (US "Administración del schema de formularios
 * dinámicos"): CRUD admin de FormTemplate/FormField/RiskRule para un
 * Request Type.
 *
 * Decisión de diseño acordada con el PO: un FormTemplate, una vez guardado,
 * NUNCA se edita in-place. La primera vez que se define un formulario para
 * un Request Type se crea la versión 1. Cualquier guardado posterior
 * ("agregar o modificar una pregunta") crea una nueva versión (version+1),
 * la marca active=true y desactiva la anterior (active=false, sin
 * borrarla).
 *
 * Esto es indispensable porque {@code Ticket.formTemplateId} congela a qué
 * versión pertenece cada ticket ya creado (Entidades V1.49 §4/§6) —
 * FormValidationService/RiskCalculationService resuelven siempre por ese id
 * congelado, nunca por "la versión activa actual". Mutar una versión que ya
 * usó algún ticket reescribiría retroactivamente el significado de tickets
 * históricos. Al crear un ticket, TicketService ya resuelve la versión más
 * reciente ACTIVA a través de
 * {@code FormTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc}
 * — no hace falta tocar ese camino, alcanza con dejar siempre la versión
 * nueva como la única activa.
 *
 * Las tres piezas que arma un formulario (A: la pregunta, B: sus posibles
 * respuestas, C: el riesgo de cada respuesta) quedan separadas tal como ya
 * estaban modeladas: FormField (columnas propias) para A, FormField.config
 * (JSON, cuya forma depende de FormFieldType) para B, RiskRule (entidad
 * propia, tipada) para C. Sólo B es "el schema en formato JSON" del AC.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FormAdminService {
    private static final BigDecimal NEGATIVE_INFINITY = new BigDecimal("-999999999999");
    private static final BigDecimal POSITIVE_INFINITY = new BigDecimal("999999999999");

    private final RequestTypeRepository requestTypeRepository;
    private final FormTemplateRepository formTemplateRepository;
    private final FormFieldRepository formFieldRepository;
    private final RiskRuleRepository riskRuleRepository;

    @Transactional(readOnly = true)
    public FormTemplateAdminResponse getForm(Long requestTypeId) {
        RequestType requestType = requireRequestType(requestTypeId);
        FormTemplate template = formTemplateRepository
                .findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestType.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El Request Type no tiene un formulario configurado"));

        List<FormField> fields =
                formFieldRepository.findAllByFormTemplate_IdOrderByDisplayOrderAsc(template.getId());
        Map<Long, List<RiskRule>> rulesByFieldId = riskRuleRepository
                .findAllByFormField_FormTemplate_Id(template.getId()).stream()
                .collect(groupingBy(rule -> rule.getFormField().getId()));
        return toResponse(template, fields, rulesByFieldId);
    }

    public FormTemplateAdminResponse saveForm(Long requestTypeId, FormTemplateAdminRequest request) {
        RequestType requestType = requireRequestType(requestTypeId);
        validate(request.getFields());

        int nextVersion = formTemplateRepository
                .findFirstByRequestType_IdOrderByVersionDesc(requestType.getId())
                .map(previous -> previous.getVersion() + 1)
                .orElse(1);

        formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestType.getId())
                .ifPresent(previousActive -> {
                    previousActive.setActive(false);
                    formTemplateRepository.save(previousActive);
                });

        FormTemplate template = new FormTemplate();
        template.setRequestType(requestType);
        template.setVersion(nextVersion);
        template.setActive(true);
        template = formTemplateRepository.save(template);

        List<FormField> savedFields = new ArrayList<>();
        Map<Long, List<RiskRule>> rulesByFieldId = new HashMap<>();
        for (FormFieldAdminRequest fieldRequest : request.getFields()) {
            FormField field = new FormField();
            field.setFormTemplate(template);
            field.setCode(fieldRequest.getCode());
            field.setLabel(fieldRequest.getLabel());
            field.setType(fieldRequest.getType());
            field.setRequired(fieldRequest.isRequired());
            field.setAllowUnknown(fieldRequest.isAllowUnknown());
            field.setDisplayOrder(fieldRequest.getDisplayOrder());
            field.setConfig(fieldRequest.getConfig() == null
                    ? new HashMap<>()
                    : new HashMap<>(fieldRequest.getConfig()));
            field = formFieldRepository.save(field);
            savedFields.add(field);

            List<RiskRule> savedRules = new ArrayList<>();
            for (RiskRuleAdminRequest ruleRequest : orEmpty(fieldRequest.getRiskRules())) {
                RiskRule rule = new RiskRule();
                rule.setFormField(field);
                rule.setOperator(ruleRequest.getOperator());
                rule.setExpectedValue(ruleRequest.getExpectedValue());
                rule.setValueFrom(ruleRequest.getValueFrom());
                rule.setValueTo(ruleRequest.getValueTo());
                rule.setRiskIncrement(ruleRequest.getRiskIncrement());
                rule.setActive(ruleRequest.isActive());
                savedRules.add(riskRuleRepository.save(rule));
            }
            rulesByFieldId.put(field.getId(), savedRules);
        }

        return toResponse(template, savedFields, rulesByFieldId);
    }

    // ---- validación del schema antes de guardar (AC: "si el JSON no es
    // válido, la modificación no se guarda" + guardrails de riesgo) ----

    private void validate(List<FormFieldAdminRequest> fields) {
        Set<String> seenCodes = new HashSet<>();
        for (FormFieldAdminRequest field : fields) {
            if (!seenCodes.add(field.getCode().toLowerCase())) {
                throw new InvalidCatalogRequestException(
                        "El código '" + field.getCode() + "' está repetido en el formulario");
            }
            validateConfig(field);
            validateRiskRules(field);
        }
    }

    private void validateConfig(FormFieldAdminRequest field) {
        if (field.getType() != FormFieldType.SELECT) {
            return;
        }
        // Misma forma que ya exige FormValidationService.validateSelect del
        // lado de lectura: si esto pasa acá, el formulario dinámico nunca
        // puede fallar por un schema mal armado.
        Map<String, Object> config = field.getConfig();
        Object rawOptions = config == null ? null : config.get("options");
        if (!(rawOptions instanceof List<?> options) || options.isEmpty()) {
            throw new InvalidCatalogRequestException(
                    "El campo '" + field.getCode() + "' es SELECT: su schema debe incluir 'options' "
                            + "como una lista no vacía");
        }
        for (Object option : options) {
            if (!(option instanceof Map<?, ?> optionMap)
                    || optionMap.get("value") == null
                    || optionMap.get("label") == null) {
                throw new InvalidCatalogRequestException(
                        "El campo '" + field.getCode() + "' tiene una opción sin 'value' o 'label'");
            }
        }
    }

    private void validateRiskRules(FormFieldAdminRequest field) {
        List<RiskRuleAdminRequest> rules = orEmpty(field.getRiskRules());
        if (rules.isEmpty()) {
            return;
        }
        // Mismo universo que RiskCalculationService.requireRiskCompatibleType,
        // pero rechazado ACÁ (admin, 400) en vez de recién al crear un
        // ticket (vecino, 500).
        if (field.getType() != FormFieldType.BOOLEAN
                && field.getType() != FormFieldType.SELECT
                && field.getType() != FormFieldType.NUMBER) {
            throw new InvalidCatalogRequestException(
                    "El campo '" + field.getCode() + "' es de tipo " + field.getType()
                            + " y no admite reglas de riesgo (sólo BOOLEAN, SELECT o NUMBER)");
        }

        for (RiskRuleAdminRequest rule : rules) {
            validateRuleShape(field, rule);
        }

        List<RiskRuleAdminRequest> activeRules = rules.stream().filter(RiskRuleAdminRequest::isActive).toList();
        for (int i = 0; i < activeRules.size(); i++) {
            for (int j = i + 1; j < activeRules.size(); j++) {
                if (rulesOverlap(activeRules.get(i), activeRules.get(j))) {
                    throw new InvalidCatalogRequestException(
                            "El campo '" + field.getCode() + "' tiene dos reglas de riesgo activas "
                                    + "que pueden coincidir con la misma respuesta");
                }
            }
        }
    }

    private void validateRuleShape(FormFieldAdminRequest field, RiskRuleAdminRequest rule) {
        switch (rule.getOperator()) {
            case EQUALS -> requireRule(field, rule.getExpectedValue() != null,
                    "EQUALS necesita expectedValue");
            case IN -> requireRule(field,
                    rule.getExpectedValue() instanceof Collection<?> values && !values.isEmpty(),
                    "IN necesita expectedValue como una lista no vacía");
            case BETWEEN -> requireRule(field,
                    rule.getValueFrom() != null && rule.getValueTo() != null
                            && rule.getValueFrom().compareTo(rule.getValueTo()) <= 0,
                    "BETWEEN necesita valueFrom y valueTo, con valueFrom <= valueTo");
            case GREATER_THAN -> requireRule(field, rule.getValueFrom() != null,
                    "GREATER_THAN necesita valueFrom");
            case LESS_THAN -> requireRule(field, rule.getValueTo() != null,
                    "LESS_THAN necesita valueTo");
        }
    }

    private void requireRule(FormFieldAdminRequest field, boolean valid, String reason) {
        if (!valid) {
            throw new InvalidCatalogRequestException(
                    "El campo '" + field.getCode() + "' tiene una regla de riesgo inválida: " + reason);
        }
    }

    /**
     * Detección pragmática de solapamiento entre reglas ACTIVAS del mismo
     * campo: no es un solver de restricciones general, pero cubre el caso
     * real que importa — dos reglas que podrían matchear la misma
     * respuesta. Hoy eso sólo lo detecta RiskCalculationService en tiempo
     * de ejecución, con un IllegalStateException que el vecino ve como un
     * 500 al crear el ticket; acá se rechaza antes de guardar, con un 400
     * para el admin que configuró mal la regla.
     */
    private boolean rulesOverlap(RiskRuleAdminRequest a, RiskRuleAdminRequest b) {
        boolean discreteA = isDiscrete(a);
        boolean discreteB = isDiscrete(b);

        if (discreteA && discreteB) {
            return !intersection(valuesOf(a), valuesOf(b)).isEmpty();
        }
        if (discreteA || discreteB) {
            RiskRuleAdminRequest discrete = discreteA ? a : b;
            RiskRuleAdminRequest range = discreteA ? b : a;
            return valuesOf(discrete).stream().anyMatch(value ->
                    value instanceof Number number && inRange(new BigDecimal(number.toString()), range));
        }
        BigDecimal maxLow = max(lowerBound(a), lowerBound(b));
        BigDecimal minHigh = min(upperBound(a), upperBound(b));
        return maxLow.compareTo(minHigh) <= 0;
    }

    private boolean isDiscrete(RiskRuleAdminRequest rule) {
        return rule.getOperator() == RiskOperator.EQUALS || rule.getOperator() == RiskOperator.IN;
    }

    private Set<Object> valuesOf(RiskRuleAdminRequest rule) {
        if (rule.getOperator() == RiskOperator.IN && rule.getExpectedValue() instanceof Collection<?> values) {
            return new HashSet<>(values);
        }
        return rule.getExpectedValue() == null ? Set.of() : Set.of(rule.getExpectedValue());
    }

    private Set<Object> intersection(Set<Object> a, Set<Object> b) {
        Set<Object> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private boolean inRange(BigDecimal value, RiskRuleAdminRequest range) {
        return value.compareTo(lowerBound(range)) >= 0 && value.compareTo(upperBound(range)) <= 0;
    }

    private BigDecimal lowerBound(RiskRuleAdminRequest rule) {
        return switch (rule.getOperator()) {
            case BETWEEN, GREATER_THAN -> rule.getValueFrom();
            case LESS_THAN, EQUALS, IN -> NEGATIVE_INFINITY;
        };
    }

    private BigDecimal upperBound(RiskRuleAdminRequest rule) {
        return switch (rule.getOperator()) {
            case BETWEEN, LESS_THAN -> rule.getValueTo();
            case GREATER_THAN, EQUALS, IN -> POSITIVE_INFINITY;
        };
    }

    private BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private List<RiskRuleAdminRequest> orEmpty(List<RiskRuleAdminRequest> list) {
        return list == null ? List.of() : list;
    }

    private RequestType requireRequestType(Long requestTypeId) {
        return requestTypeRepository.findById(requestTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("El Request Type solicitado no existe"));
    }

    // ---- mapping a response ----

    private FormTemplateAdminResponse toResponse(FormTemplate template, List<FormField> fields,
                                                  Map<Long, List<RiskRule>> rulesByFieldId) {
        FormTemplateAdminResponse response = new FormTemplateAdminResponse();
        response.setId(template.getId());
        response.setRequestTypeId(template.getRequestType().getId());
        response.setVersion(template.getVersion());
        response.setActive(template.getActive());
        response.setCreatedAt(template.getCreatedAt());
        response.setFields(fields.stream()
                .map(field -> toResponse(field, rulesByFieldId.getOrDefault(field.getId(), List.of())))
                .toList());
        return response;
    }

    private FormFieldAdminResponse toResponse(FormField field, List<RiskRule> rules) {
        FormFieldAdminResponse response = new FormFieldAdminResponse();
        response.setId(field.getId());
        response.setCode(field.getCode());
        response.setLabel(field.getLabel());
        response.setType(field.getType());
        response.setRequired(field.isRequired());
        response.setAllowUnknown(field.isAllowUnknown());
        response.setDisplayOrder(field.getDisplayOrder());
        response.setConfig(field.getConfig());
        response.setRiskRules(rules.stream().map(this::toResponse).toList());
        return response;
    }

    private RiskRuleAdminResponse toResponse(RiskRule rule) {
        RiskRuleAdminResponse response = new RiskRuleAdminResponse();
        response.setId(rule.getId());
        response.setOperator(rule.getOperator());
        response.setExpectedValue(rule.getExpectedValue());
        response.setValueFrom(rule.getValueFrom());
        response.setValueTo(rule.getValueTo());
        response.setRiskIncrement(rule.getRiskIncrement());
        response.setActive(rule.isActive());
        return response;
    }
}
