package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.FormFieldType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FormFieldAdminRequest {
    @NotBlank(message = "code es obligatorio")
    @Size(max = 100, message = "code no puede superar los 100 caracteres")
    private String code;

    @NotBlank(message = "label es obligatorio")
    @Size(max = 200, message = "label no puede superar los 200 caracteres")
    private String label;

    @NotNull(message = "type es obligatorio")
    private FormFieldType type;

    private boolean required;

    private boolean allowUnknown;

    @NotNull(message = "displayOrder es obligatorio")
    private Integer displayOrder;

    // La "pregunta" (code/label/type/...) son columnas propias — esto es
    // sólo la parte que varía según el type (ej. las opciones de un
    // SELECT). Es lo que el AC llama "el schema en formato JSON".
    private Map<String, Object> config;

    @Valid
    private List<RiskRuleAdminRequest> riskRules;
}
