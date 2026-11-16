package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.FormFieldAdminRequest;
import com.reclamos.backend.dto.request.FormTemplateAdminRequest;
import com.reclamos.backend.dto.request.RiskRuleAdminRequest;
import com.reclamos.backend.dto.response.FormTemplateAdminResponse;
import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.RiskOperator;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.InvalidCatalogRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.RiskRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE - DDA2-133/134/135. Cubre el versionado (nunca se edita in-place: la
 * primera vez es v1, cada guardado siguiente crea v+1 y desactiva la
 * anterior) y los guardrails de riesgo (RiskRule) que hoy
 * RiskCalculationService sólo detecta en tiempo de ejecución.
 */
@ExtendWith(MockitoExtension.class)
class FormAdminServiceTest {
    @Mock
    private RequestTypeRepository requestTypeRepository;
    @Mock
    private FormTemplateRepository formTemplateRepository;
    @Mock
    private FormFieldRepository formFieldRepository;
    @Mock
    private RiskRuleRepository riskRuleRepository;

    private FormAdminService service;
    private long nextId;

    @BeforeEach
    void setUp() {
        service = new FormAdminService(requestTypeRepository, formTemplateRepository, formFieldRepository,
                riskRuleRepository);
        nextId = 1L;
        lenient().when(formTemplateRepository.save(any(FormTemplate.class))).thenAnswer(invocation -> {
            FormTemplate template = invocation.getArgument(0);
            if (template.getId() == null) {
                template.setId(nextId++);
            }
            return template;
        });
        // saveForm() usa saveAndFlush() (no save()) para desactivar la
        // versión previa — ver el comentario en FormAdminService.saveForm
        // sobre uk_form_template_active_request_type + IDENTITY insert.
        lenient().when(formTemplateRepository.saveAndFlush(any(FormTemplate.class))).thenAnswer(invocation -> {
            FormTemplate template = invocation.getArgument(0);
            if (template.getId() == null) {
                template.setId(nextId++);
            }
            return template;
        });
        lenient().when(formFieldRepository.save(any(FormField.class))).thenAnswer(invocation -> {
            FormField field = invocation.getArgument(0);
            if (field.getId() == null) {
                field.setId(nextId++);
            }
            return field;
        });
        lenient().when(riskRuleRepository.save(any(RiskRule.class))).thenAnswer(invocation -> {
            RiskRule rule = invocation.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(nextId++);
            }
            return rule;
        });
    }

    // ---- lectura ----

    @Test
    void getFormReturnsActiveTemplateWithFieldsAndRiskRules() {
        RequestType requestType = requestType(1L);
        FormTemplate template = formTemplate(10L, requestType, 2, true);
        FormField field = formField(100L, template, "TIENE_AGUA", FormFieldType.BOOLEAN, Map.of());
        RiskRule rule = riskRule(1000L, field, RiskOperator.EQUALS, true, null, null, (short) 20, true);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType));
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.of(template));
        when(formFieldRepository.findAllByFormTemplate_IdOrderByDisplayOrderAsc(10L)).thenReturn(List.of(field));
        when(riskRuleRepository.findAllByFormField_FormTemplate_Id(10L)).thenReturn(List.of(rule));

        FormTemplateAdminResponse response = service.getForm(1L);

        assertEquals(2, response.getVersion());
        assertTrue(response.isActive());
        assertEquals(1, response.getFields().size());
        assertEquals("TIENE_AGUA", response.getFields().get(0).getCode());
        assertEquals(1, response.getFields().get(0).getRiskRules().size());
        assertEquals((short) 20, response.getFields().get(0).getRiskRules().get(0).getRiskIncrement());
    }

    @Test
    void getFormMissingRequestTypeThrowsNotFound() {
        when(requestTypeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getForm(99L));
    }

    @Test
    void getFormWithNoTemplateConfiguredThrowsNotFound() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getForm(1L));
    }

    // ---- versionado ----

    @Test
    void saveFormFirstSaveCreatesVersion1Active() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));
        when(formTemplateRepository.findFirstByRequestType_IdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());

        FormTemplateAdminResponse response = service.saveForm(1L,
                templateRequest(fieldRequest("TIENE_AGUA", FormFieldType.BOOLEAN, null, null)));

        assertEquals(1, response.getVersion());
        assertTrue(response.isActive());
        assertEquals(1, response.getFields().size());
        verify(formTemplateRepository, times(1)).save(any());
    }

    @Test
    void saveFormSecondSaveCreatesVersion2AndDeactivatesVersion1() {
        RequestType requestType = requestType(1L);
        FormTemplate v1 = formTemplate(10L, requestType, 1, true);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType));
        when(formTemplateRepository.findFirstByRequestType_IdOrderByVersionDesc(1L)).thenReturn(Optional.of(v1));
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.of(v1));

        FormTemplateAdminResponse response = service.saveForm(1L,
                templateRequest(fieldRequest("TIENE_AGUA", FormFieldType.BOOLEAN, null, null)));

        assertEquals(2, response.getVersion());
        assertTrue(response.isActive());
        assertFalse(v1.getActive());
        // Regresión: tiene que ser saveAndFlush, no save() — con save() el
        // UPDATE que desactiva v1 queda diferido hasta el commit y el
        // INSERT (inmediato por IDENTITY) de v2 pisa
        // uk_form_template_active_request_type en la base real. Un mock no
        // distingue esto por sí solo, así que lo afirmamos explícitamente.
        verify(formTemplateRepository).saveAndFlush(v1);
        verify(formTemplateRepository, never()).save(v1);
    }

    @Test
    void saveFormMissingRequestTypeThrowsNotFound() {
        when(requestTypeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.saveForm(99L,
                templateRequest(fieldRequest("X", FormFieldType.TEXT, null, null))));
    }

    // ---- validación del schema (B) ----

    @Test
    void saveFormRejectsDuplicateFieldCodes() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("TIENE_AGUA", FormFieldType.BOOLEAN, null, null),
                fieldRequest("tiene_agua", FormFieldType.TEXT, null, null));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
        verify(formTemplateRepository, never()).save(any());
    }

    @Test
    void saveFormRejectsSelectFieldWithoutOptions() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("NIVEL", FormFieldType.SELECT, Map.of(), null));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsSelectOptionMissingValueOrLabel() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        Map<String, Object> config = Map.of("options", List.of(Map.of("value", "BAJO")));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("NIVEL", FormFieldType.SELECT, config, null));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    // ---- validación de RiskRule (C) ----

    @Test
    void saveFormRejectsRiskRuleOnTextField() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.EQUALS, "algo", null, null, (short) 10, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("COMENTARIO", FormFieldType.TEXT, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsEqualsRuleWithoutExpectedValue() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.EQUALS, null, null, null, (short) 10, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("TIENE_AGUA", FormFieldType.BOOLEAN, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsInRuleWithEmptyExpectedValue() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.IN, List.of(), null, null, (short) 10, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("NIVEL", FormFieldType.SELECT,
                        Map.of("options", List.of(Map.of("value", "BAJO", "label", "Bajo"))), rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsBetweenRuleWithFromGreaterThanTo() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.BETWEEN, null, BigDecimal.TEN, BigDecimal.ONE, (short) 10, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsGreaterThanRuleWithoutValueFrom() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.GREATER_THAN, null, null, null, (short) 10, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsLessThanRuleWithoutValueTo() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.LESS_THAN, null, null, null, (short) 10, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsOverlappingEqualsRules() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.EQUALS, "ALTO", null, null, (short) 10, true),
                ruleRequest(RiskOperator.EQUALS, "ALTO", null, null, (short) 20, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("NIVEL", FormFieldType.SELECT,
                        Map.of("options", List.of(Map.of("value", "ALTO", "label", "Alto"))), rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormRejectsOverlappingEqualsRulesWithNumericallyEquivalentValuesOfDifferentJavaType() {
        // QA (FAIL de "validación del formato JSON antes de persistir
        // modificaciones"): 5 (Integer, como deserializa Jackson un JSON sin
        // punto decimal en un campo Object) y 5.0 (Double, con punto
        // decimal) son el mismo valor NUMBER, pero antes del fix
        // Integer(5).equals(Double(5.0)) == false hacía que esto pasara
        // como "no solapan". Acá se simula exactamente esa mezcla de tipos
        // Java, no dos BigDecimal con distinta escala.
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.EQUALS, 5, null, null, (short) 10, true),
                ruleRequest(RiskOperator.EQUALS, 5.0, null, null, (short) 20, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
        verify(formTemplateRepository, never()).save(any());
    }

    @Test
    void saveFormAllowsEqualsRulesWithGenuinelyDifferentNumericValues() {
        // Control: valores realmente distintos (5 vs 6) no deben rechazarse
        // por el fix de comparación numérica.
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));
        when(formTemplateRepository.findFirstByRequestType_IdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());

        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.EQUALS, 5, null, null, (short) 10, true),
                ruleRequest(RiskOperator.EQUALS, 6.0, null, null, (short) 20, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        FormTemplateAdminResponse response = service.saveForm(1L, request);

        assertEquals(2, response.getFields().get(0).getRiskRules().size());
    }

    @Test
    void saveFormRejectsOverlappingNumericRanges() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));

        // [1,5] se pisa con (3, +inf) en el tramo [3,5].
        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.BETWEEN, null, BigDecimal.valueOf(1), BigDecimal.valueOf(5),
                        (short) 10, true),
                ruleRequest(RiskOperator.GREATER_THAN, null, BigDecimal.valueOf(3), null, (short) 20, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        assertThrows(InvalidCatalogRequestException.class, () -> service.saveForm(1L, request));
    }

    @Test
    void saveFormAllowsNonOverlappingRanges() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));
        when(formTemplateRepository.findFirstByRequestType_IdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());

        // [1,5] y (6, +inf) no se tocan.
        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.BETWEEN, null, BigDecimal.valueOf(1), BigDecimal.valueOf(5),
                        (short) 10, true),
                ruleRequest(RiskOperator.GREATER_THAN, null, BigDecimal.valueOf(6), null, (short) 20, true));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("CANTIDAD", FormFieldType.NUMBER, null, rules));

        FormTemplateAdminResponse response = service.saveForm(1L, request);

        assertEquals(2, response.getFields().get(0).getRiskRules().size());
    }

    @Test
    void saveFormIgnoresOverlapAmongInactiveRules() {
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(requestType(1L)));
        when(formTemplateRepository.findFirstByRequestType_IdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(formTemplateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());

        // La segunda regla repite el mismo valor pero está inactiva: sólo
        // importa el solapamiento entre reglas ACTIVAS.
        List<RiskRuleAdminRequest> rules = List.of(
                ruleRequest(RiskOperator.EQUALS, "ALTO", null, null, (short) 10, true),
                ruleRequest(RiskOperator.EQUALS, "ALTO", null, null, (short) 20, false));
        FormTemplateAdminRequest request = templateRequest(
                fieldRequest("NIVEL", FormFieldType.SELECT,
                        Map.of("options", List.of(Map.of("value", "ALTO", "label", "Alto"))), rules));

        FormTemplateAdminResponse response = service.saveForm(1L, request);

        assertEquals(2, response.getFields().get(0).getRiskRules().size());
    }

    // ---- helpers ----

    private FormTemplateAdminRequest templateRequest(FormFieldAdminRequest... fields) {
        FormTemplateAdminRequest request = new FormTemplateAdminRequest();
        request.setFields(List.of(fields));
        return request;
    }

    private FormFieldAdminRequest fieldRequest(String code, FormFieldType type, Map<String, Object> config,
                                                List<RiskRuleAdminRequest> riskRules) {
        FormFieldAdminRequest request = new FormFieldAdminRequest();
        request.setCode(code);
        request.setLabel(code + " label");
        request.setType(type);
        request.setRequired(true);
        request.setDisplayOrder(1);
        request.setConfig(config);
        request.setRiskRules(riskRules);
        return request;
    }

    private RiskRuleAdminRequest ruleRequest(RiskOperator operator, Object expectedValue, BigDecimal valueFrom,
                                              BigDecimal valueTo, short increment, boolean active) {
        RiskRuleAdminRequest request = new RiskRuleAdminRequest();
        request.setOperator(operator);
        request.setExpectedValue(expectedValue);
        request.setValueFrom(valueFrom);
        request.setValueTo(valueTo);
        request.setRiskIncrement(increment);
        request.setActive(active);
        return request;
    }

    private RequestType requestType(Long id) {
        RequestType requestType = new RequestType();
        requestType.setId(id);
        requestType.setCode("BACHE");
        requestType.setName("Informar bache");
        requestType.setDescription("desc");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M3");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setBaseRisk(Risk.LOW);
        requestType.setAffectedPopulationFactor(BigDecimal.valueOf(0.1));
        requestType.setAllowsAnonymous(true);
        requestType.setRequiresLocation(true);
        requestType.setActive(true);
        return requestType;
    }

    private FormTemplate formTemplate(Long id, RequestType requestType, int version, boolean active) {
        FormTemplate template = new FormTemplate();
        template.setId(id);
        template.setRequestType(requestType);
        template.setVersion(version);
        template.setActive(active);
        return template;
    }

    private FormField formField(Long id, FormTemplate template, String code, FormFieldType type,
                                 Map<String, Object> config) {
        FormField field = new FormField();
        field.setId(id);
        field.setFormTemplate(template);
        field.setCode(code);
        field.setLabel(code + " label");
        field.setType(type);
        field.setRequired(true);
        field.setDisplayOrder(1);
        field.setConfig(config == null ? Map.of() : config);
        return field;
    }

    private RiskRule riskRule(Long id, FormField field, RiskOperator operator, Object expectedValue,
                               BigDecimal valueFrom, BigDecimal valueTo, short increment, boolean active) {
        RiskRule rule = new RiskRule();
        rule.setId(id);
        rule.setFormField(field);
        rule.setOperator(operator);
        rule.setExpectedValue(expectedValue);
        rule.setValueFrom(valueFrom);
        rule.setValueTo(valueTo);
        rule.setRiskIncrement(increment);
        rule.setActive(active);
        return rule;
    }
}
