package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.RiskOperator;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RiskRuleAdminRequest {
    @NotNull(message = "operator es obligatorio")
    private RiskOperator operator;

    // Lo que exige cada operador (EQUALS/IN necesitan expectedValue,
    // BETWEEN/GREATER_THAN/LESS_THAN necesitan valueFrom/valueTo) se valida
    // en el servicio, no acá con anotaciones — depende de qué operador se
    // elija, no es un campo obligatorio fijo.
    private Object expectedValue;

    private BigDecimal valueFrom;

    private BigDecimal valueTo;

    @NotNull(message = "riskIncrement es obligatorio")
    private Short riskIncrement;

    private boolean active = true;
}
