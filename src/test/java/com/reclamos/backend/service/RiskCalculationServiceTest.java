package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskCalculationServiceTest {
    private final RiskCalculationService service = new RiskCalculationService();

    @Test
    void readsOnlyRiskScoreFromSelectedOption() {
        FormField field = select(Map.of(
                "value", "YES", "riskScore", 3,
                "score", 5, "risk", "CRITICAL"));
        assertEquals(Risk.HIGH, calculate(Risk.LOW, Map.of("answer", "YES"), field));
    }

    @Test
    void ignoresLegacyScoreAndRiskAliases() {
        FormField field = select(Map.of("value", "YES", "score", 5, "risk", "CRITICAL"));
        assertEquals(Risk.LOW, calculate(Risk.LOW, Map.of("answer", "YES"), field));
    }

    @Test
    void sumsScoresCapsAtFiveAndMapsDefinedBoundaries() {
        assertEquals(Risk.LOW, calculate(Risk.LOW, Map.of("a", true), field("a", 1.99)));
        assertEquals(Risk.MEDIUM, calculate(Risk.LOW, Map.of("a", true), field("a", 2)));
        assertEquals(Risk.HIGH, calculate(Risk.LOW, Map.of("a", true), field("a", 3)));
        assertEquals(Risk.CRITICAL, calculate(Risk.LOW, Map.of("a", true), field("a", 4)));
        assertEquals(Risk.CRITICAL, calculate(Risk.LOW, Map.of("a", true, "b", true),
                field("a", 4), field("b", 4)));
    }

    @Test
    void requestTypeBaseRiskIsAlwaysAppliedAsFloor() {
        assertEquals(Risk.HIGH, calculate(Risk.HIGH, Map.of("a", true), field("a", 0)));
    }

    private Risk calculate(Risk baseRisk, Map<String, Object> data, FormField... fields) {
        RequestType requestType = new RequestType();
        requestType.setBaseRisk(baseRisk);
        return service.calculateRisk(requestType, data, List.of(fields));
    }

    private FormField select(Map<String, Object> option) {
        FormField field = field("answer", 0);
        field.setType(FormFieldType.SELECT);
        field.setConfig(new HashMap<>(Map.of("options", List.of(option))));
        return field;
    }

    private FormField field(String code, double riskScore) {
        FormField field = new FormField();
        field.setCode(code);
        field.setType(FormFieldType.BOOLEAN);
        field.setConfig(new HashMap<>(Map.of("riskScore", riskScore)));
        return field;
    }
}