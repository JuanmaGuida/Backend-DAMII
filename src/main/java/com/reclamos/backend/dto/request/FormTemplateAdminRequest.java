package com.reclamos.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class FormTemplateAdminRequest {
    @NotEmpty(message = "El formulario debe tener al menos una pregunta")
    @Valid
    private List<FormFieldAdminRequest> fields;
}
