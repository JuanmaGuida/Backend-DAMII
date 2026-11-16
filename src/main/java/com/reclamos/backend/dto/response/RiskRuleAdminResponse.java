package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.RiskOperator;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RiskRuleAdminResponse {
    private Long id;
    private RiskOperator operator;
    private Object expectedValue;
    private BigDecimal valueFrom;
    private BigDecimal valueTo;
    private short riskIncrement;
    private boolean active;
}
