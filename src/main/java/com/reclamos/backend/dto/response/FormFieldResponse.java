package com.reclamos.backend.dto.response;

import com.reclamos.backend.entity.FormFieldType;
import lombok.Data;

import java.util.Map;

@Data
public class FormFieldResponse {
    private String code;
    private String label;
    private FormFieldType type;
    private Boolean required;
    private Integer displayOrder;
    private Map<String, Object> config;
}
