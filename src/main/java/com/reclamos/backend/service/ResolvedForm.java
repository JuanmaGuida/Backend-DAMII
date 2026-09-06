package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ResolvedForm(FormTemplate template, List<FormField> fields, Map<String, Object> formData) {
    public ResolvedForm {
        fields = List.copyOf(fields);
        formData = Collections.unmodifiableMap(new LinkedHashMap<>(formData));
    }

    public Long formTemplateId() {
        return template == null ? null : template.getId();
    }
}
