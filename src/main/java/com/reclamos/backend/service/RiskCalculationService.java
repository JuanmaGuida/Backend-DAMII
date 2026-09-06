package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RiskRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskCalculationService {
    private final RiskRuleRepository riskRuleRepository;
    private final FormTemplateRepository formTemplateRepository;
    private final RiskScalePolicy riskScalePolicy;

    public RiskAssessment calculateRisk(RequestType requestType, ResolvedForm resolvedForm) {
        return calculateRisk(requestType, resolvedForm.formTemplateId(), resolvedForm.formData());
    }

    public RiskAssessment calculateRisk(Ticket ticket) {
        Long formTemplateId = ticket.getFormTemplateId();
        RequestType requestType = ticket.getRequestType();
        if (formTemplateId != null
                && !formTemplateRepository.existsByIdAndRequestType_Id(formTemplateId, requestType.getId())) {
            throw new IllegalStateException("El formulario histórico no pertenece al Request Type del ticket");
        }
        return calculateRisk(requestType, formTemplateId, ticket.getFormData());
    }

    private RiskAssessment calculateRisk(RequestType requestType, Long formTemplateId,
                                         Map<String, Object> formData) {
        int baseScore = riskScalePolicy.baseScore(requestType.getBaseRisk());
        Map<String, Object> data = formData == null ? Collections.emptyMap() : formData;
        List<RiskRule> rules = formTemplateId == null
                ? List.of()
                : riskRuleRepository.findAllByFormField_FormTemplate_IdAndActiveTrue(formTemplateId);

        int incrementSum = 0;
        for (RiskRule rule : rules) {
            if (!rule.isActive()
                    || !Objects.equals(rule.getFormField().getFormTemplate().getId(), formTemplateId)) {
                continue;
            }
            String fieldCode = rule.getFormField().getCode();
            if (!data.containsKey(fieldCode)) {
                continue;
            }
            Object answer = data.get(fieldCode);
            if (answer != null && matches(rule, answer)) {
                incrementSum += rule.getRiskIncrement();
            }
        }
        int score = riskScalePolicy.cap(baseScore + incrementSum);
        return new RiskAssessment(score, riskScalePolicy.classify(score));
    }

    private boolean matches(RiskRule rule, Object answer) {
        return switch (rule.getOperator()) {
            case EQUALS -> equalsTyped(rule.getFormField().getType(), answer, rule.getExpectedValue());
            case BETWEEN -> compareNumber(answer, rule.getValueFrom(), comparison -> comparison >= 0)
                    && compareNumber(answer, rule.getValueTo(), comparison -> comparison <= 0);
            case GREATER_THAN -> compareNumber(answer, rule.getValueFrom(), comparison -> comparison > 0);
            case LESS_THAN -> compareNumber(answer, rule.getValueTo(), comparison -> comparison < 0);
            case IN -> rule.getExpectedValue() instanceof Collection<?> values
                    && values.stream().anyMatch(value ->
                    equalsTyped(rule.getFormField().getType(), answer, value));
        };
    }

    private boolean equalsTyped(FormFieldType type, Object answer, Object expected) {
        if (expected == null) {
            return false;
        }
        return switch (type) {
            case NUMBER -> answer instanceof Number && expected instanceof Number
                    && toBigDecimal((Number) answer).compareTo(toBigDecimal((Number) expected)) == 0;
            case BOOLEAN -> answer instanceof Boolean && expected instanceof Boolean
                    && answer.equals(expected);
            case TEXT, TEXTAREA, SELECT, DATE -> answer instanceof String && expected instanceof String
                    && answer.equals(expected);
        };
    }

    private boolean compareNumber(Object answer, BigDecimal limit,
                                  java.util.function.IntPredicate predicate) {
        return answer instanceof Number number && limit != null
                && predicate.test(toBigDecimal(number).compareTo(limit));
    }

    private BigDecimal toBigDecimal(Number value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }
}
