package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RiskCalculationService {
    public Risk calculateRisk(RequestType requestType, Map<String, Object> formData, List<FormField> fields) {
        double score = Math.min(5, fields.stream()
                .mapToDouble(field -> scoreFor(field, formData.get(field.getCode())))
                .sum());
        Risk calculated = score < 2 ? Risk.LOW : score < 3 ? Risk.MEDIUM
                : score < 4 ? Risk.HIGH : Risk.CRITICAL;
        return calculated.ordinal() < requestType.getBaseRisk().ordinal()
                ? requestType.getBaseRisk() : calculated;
    }

    private double scoreFor(FormField field, Object answer) {
        if (answer == null || field.getConfig() == null) {
            return 0;
        }
        Object configured = field.getConfig().get("riskScore");
        if (field.getType() == FormFieldType.SELECT && field.getConfig().get("options") instanceof List<?> options) {
            configured = options.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(option -> answer.equals(option.get("value")))
                    .findFirst().map(option -> option.get("riskScore")).orElse(null);
        }
        return configured instanceof Number number
                ? Math.max(0, Math.min(5, number.doubleValue())) : 0;
    }
}