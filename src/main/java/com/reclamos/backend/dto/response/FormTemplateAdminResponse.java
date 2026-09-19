package com.reclamos.backend.dto.response;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class FormTemplateAdminResponse {
    private Long id;
    private Long requestTypeId;
    private Integer version;
    private boolean active;
    private Instant createdAt;
    private List<FormFieldAdminResponse> fields;
}
