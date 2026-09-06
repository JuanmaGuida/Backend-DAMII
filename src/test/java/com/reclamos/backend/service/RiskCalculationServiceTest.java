package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.RiskOperator;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RiskRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskCalculationServiceTest {
    private final RiskRuleRepository rules = mock(RiskRuleRepository.class);
    private final FormTemplateRepository templates = mock(FormTemplateRepository.class);
    private final RiskScalePolicy policy = new RiskScalePolicy();
    private final FormTemplate template = template(11L);
    private RiskCalculationService service;

    @BeforeEach
    void setUp() {
        service = new RiskCalculationService(rules, templates, policy);
    }

    @Test
    void emptyOrOmittedAnswersReturnExactBaseRisk() {
        assertAssessment(Risk.LOW, Map.of(), List.of(), 0, Risk.LOW);
        assertAssessment(Risk.MEDIUM, Map.of(), List.of(), 25, Risk.MEDIUM);
        assertAssessment(Risk.HIGH, Map.of(), List.of(), 50, Risk.HIGH);
        assertAssessment(Risk.CRITICAL, Map.of(), List.of(), 75, Risk.CRITICAL);

        RiskRule presentOnly = rule(field("danger", FormFieldType.BOOLEAN, template),
                RiskOperator.EQUALS, true, null, null, 18);
        assertAssessment(Risk.LOW, Map.of(), List.of(presentOnly), 0, Risk.LOW);
    }

    @Test
    void nullAndFalseDoNotMatchEqualsTrueWhileNumericZeroRemainsPresent() {
        FormField danger = field("danger", FormFieldType.BOOLEAN, template);
        FormField amount = field("amount", FormFieldType.NUMBER, template);
        RiskRule booleanRule = rule(danger, RiskOperator.EQUALS, true, null, null, 18);
        RiskRule zeroRule = rule(amount, RiskOperator.EQUALS, 0, null, null, 10);
        List<RiskRule> configuredRules = List.of(booleanRule, zeroRule);

        Map<String, Object> nullAnswer = new HashMap<>();
        nullAnswer.put("danger", null);
        assertAssessment(Risk.LOW, nullAnswer, configuredRules, 0, Risk.LOW);
        assertAssessment(Risk.LOW, Map.of("danger", false), configuredRules, 0, Risk.LOW);
        assertAssessment(Risk.LOW, Map.of("danger", true), configuredRules, 18, Risk.LOW);
        assertAssessment(Risk.LOW, Map.of("amount", 0), configuredRules, 10, Risk.LOW);
    }

    @Test
    void approvedAccumulationExamplesProduceExpectedScoresAndCategories() {
        assertAssessment(Risk.LOW, Map.of("a", true, "b", true),
                List.of(booleanRule("a", 13), booleanRule("b", 13)), 26, Risk.MEDIUM);
        assertAssessment(Risk.LOW, Map.of("a", true),
                List.of(booleanRule("a", 36)), 36, Risk.MEDIUM);
        assertAssessment(Risk.LOW, Map.of("a", true, "b", true, "c", true),
                List.of(booleanRule("a", 13), booleanRule("b", 13), booleanRule("c", 36)),
                62, Risk.HIGH);
        assertAssessment(Risk.MEDIUM, Map.of("a", true),
                List.of(booleanRule("a", 27)), 52, Risk.HIGH);
        assertAssessment(Risk.HIGH, Map.of("a", true),
                List.of(booleanRule("a", 27)), 77, Risk.CRITICAL);
        assertAssessment(Risk.CRITICAL, Map.of("a", true),
                List.of(booleanRule("a", 36)), 100, Risk.CRITICAL);
    }

    @Test
    void selectDifferentInactiveAndOtherTemplateRulesAreIgnored() {
        FormField choice = field("choice", FormFieldType.SELECT, template);
        RiskRule selected = rule(choice, RiskOperator.EQUALS, "SEVERE", null, null, 36);
        RiskRule inactive = rule(choice, RiskOperator.EQUALS, "SEVERE", null, null, 27);
        inactive.setActive(false);
        RiskRule otherTemplate = rule(field("choice", FormFieldType.SELECT, template(22L)),
                RiskOperator.EQUALS, "SEVERE", null, null, 36);

        assertAssessment(Risk.LOW, Map.of("choice", "OTHER"),
                List.of(selected, inactive, otherTemplate), 0, Risk.LOW);
        assertAssessment(Risk.LOW, Map.of("choice", "SEVERE"),
                List.of(selected, inactive, otherTemplate), 36, Risk.MEDIUM);
    }

    @Test
    void numericOperatorsUseBigDecimalAndBetweenIsInclusive() {
        FormField amount = field("amount", FormFieldType.NUMBER, template);
        List<RiskRule> configuredRules = List.of(
                rule(amount, RiskOperator.BETWEEN, null,
                        new BigDecimal("10.0"), new BigDecimal("20.0"), 5),
                rule(amount, RiskOperator.GREATER_THAN, null,
                        new BigDecimal("15"), null, 10),
                rule(amount, RiskOperator.LESS_THAN, null,
                        null, new BigDecimal("21"), 13)
        );

        assertAssessment(Risk.LOW, Map.of("amount", new BigDecimal("10.00")),
                configuredRules, 18, Risk.LOW);
        assertAssessment(Risk.LOW, Map.of("amount", 20), configuredRules, 28, Risk.MEDIUM);
        assertAssessment(Risk.LOW, Map.of("amount", 21), configuredRules, 10, Risk.LOW);
    }

    @Test
    void inUsesTypedMembership() {
        FormField choice = field("choice", FormFieldType.SELECT, template);
        RiskRule in = rule(choice, RiskOperator.IN, List.of("A", "B"), null, null, 27);

        assertAssessment(Risk.LOW, Map.of("choice", "B"), List.of(in), 27, Risk.MEDIUM);
        assertAssessment(Risk.LOW, Map.of("choice", "C"), List.of(in), 0, Risk.LOW);
    }

    @Test
    void historicalTicketUsesStoredTemplateAndNeverResolvesCurrentActiveTemplate() {
        RequestType requestType = requestType(Risk.LOW);
        Ticket ticket = new Ticket();
        ticket.setRequestType(requestType);
        ticket.setFormTemplateId(11L);
        ticket.setFormData(Map.of("danger", true));
        when(templates.existsByIdAndRequestType_Id(11L, requestType.getId())).thenReturn(true);
        when(rules.findAllByFormField_FormTemplate_IdAndActiveTrue(11L))
                .thenReturn(List.of(booleanRule("danger", 36)));

        assertEquals(new RiskAssessment(36, Risk.MEDIUM), service.calculateRisk(ticket));

        verify(templates, never()).findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(requestType.getId());
    }

    @Test
    void historicalTicketRejectsTemplateFromAnotherRequestType() {
        RequestType requestType = requestType(Risk.LOW);
        Ticket ticket = new Ticket();
        ticket.setRequestType(requestType);
        ticket.setFormTemplateId(22L);
        ticket.setFormData(Map.of());
        when(templates.existsByIdAndRequestType_Id(22L, requestType.getId())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.calculateRisk(ticket));
    }

    private void assertAssessment(Risk baseRisk, Map<String, Object> data, List<RiskRule> configuredRules,
                                  int expectedScore, Risk expectedRisk) {
        when(rules.findAllByFormField_FormTemplate_IdAndActiveTrue(11L))
                .thenReturn(new ArrayList<>(configuredRules));
        RiskAssessment assessment = service.calculateRisk(requestType(baseRisk),
                new ResolvedForm(template, List.of(), data));
        assertEquals(expectedScore, assessment.score());
        assertEquals(expectedRisk, assessment.calculatedRisk());
    }

    private RiskRule booleanRule(String code, int increment) {
        return rule(field(code, FormFieldType.BOOLEAN, template),
                RiskOperator.EQUALS, true, null, null, increment);
    }

    private RiskRule rule(FormField field, RiskOperator operator, Object expected,
                          BigDecimal valueFrom, BigDecimal valueTo, int increment) {
        RiskRule rule = new RiskRule();
        rule.setFormField(field);
        rule.setOperator(operator);
        rule.setExpectedValue(expected);
        rule.setValueFrom(valueFrom);
        rule.setValueTo(valueTo);
        rule.setRiskIncrement((short) increment);
        rule.setActive(true);
        return rule;
    }

    private FormField field(String code, FormFieldType type, FormTemplate owner) {
        FormField field = new FormField();
        field.setCode(code);
        field.setType(type);
        field.setFormTemplate(owner);
        return field;
    }

    private FormTemplate template(Long id) {
        FormTemplate value = new FormTemplate();
        value.setId(id);
        return value;
    }

    private RequestType requestType(Risk baseRisk) {
        RequestType requestType = new RequestType();
        requestType.setId(7L);
        requestType.setBaseRisk(baseRisk);
        return requestType;
    }
}
