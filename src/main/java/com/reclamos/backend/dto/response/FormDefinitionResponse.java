package com.reclamos.backend.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class FormDefinitionResponse {
    private Long requestTypeId;
    private String requestTypeCode;
    private Integer version;
    private List<FormFieldResponse> fields;
}
