package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.FormFieldType;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FormFieldAdminResponse {
    private Long id;
    private String code;
    private String label;
    private FormFieldType type;
    private boolean required;
    private boolean allowUnknown;
    private Integer displayOrder;
    private Map<String, Object> config;
    private List<RiskRuleAdminResponse> riskRules;
}
