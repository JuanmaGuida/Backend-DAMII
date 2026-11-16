package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReclassificationFormValidationTest {
    final FormTemplateRepository templates = mock(FormTemplateRepository.class);
    final FormFieldRepository fields = mock(FormFieldRepository.class);
    final FormValidationService service = new FormValidationService(templates, fields);
    final RequestType type = new RequestType();
    final FormTemplate template = new FormTemplate();

    ReclassificationFormValidationTest() {
        type.setId(1L); type.setActive(true); template.setId(2L); template.setRequestType(type);
        when(templates.findById(2L)).thenReturn(Optional.of(template));
    }

    @ParameterizedTest @EnumSource(FormFieldType.class)
    void acceptsOnlyValuesValidForThePinnedVersion(FormFieldType fieldType) {
        FormField field = field(fieldType, false);
        field.setConfig(Map.of("options", List.of(Map.of("value", "A", "label", "Opción A")), "min", 0, "max", 10));
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(2L)).thenReturn(List.of(field));
        Object value = switch (fieldType) {
            case BOOLEAN -> false; case NUMBER -> 0; case DATE -> "2026-10-20";
            case SELECT -> "A"; case TEXT, TEXTAREA -> "Contexto";
        };
        assertThat(service.resolveVersion(type, 2L, Map.of("answer", value), true).formData()).containsEntry("answer", value);
        assertThatThrownBy(() -> service.resolveVersion(type, 2L, Map.of("answer", List.of()), true)).isInstanceOf(FormValidationException.class);
        assertThatThrownBy(() -> service.resolveVersion(type, 2L, Map.of("unknown", value), false)).isInstanceOf(FormValidationException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void requiredUnknownMustBeExplicitAndAllowed(boolean allowUnknown) {
        FormField field = field(FormFieldType.BOOLEAN, allowUnknown);
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(2L)).thenReturn(List.of(field));
        assertThat(service.resolveVersion(type, 2L, Map.of(), false).formData()).isEmpty();
        assertThatThrownBy(() -> service.resolveVersion(type, 2L, Map.of(), true)).isInstanceOf(FormValidationException.class);
        Map<String, Object> unknown = new HashMap<>(); unknown.put("answer", null);
        if (allowUnknown) assertThat(service.resolveVersion(type, 2L, unknown, true).formData()).containsEntry("answer", null);
        else assertThatThrownBy(() -> service.resolveVersion(type, 2L, unknown, true)).isInstanceOf(FormValidationException.class);
    }

    @ParameterizedTest @ValueSource(doubles = {-1, 11, Double.POSITIVE_INFINITY, Double.NaN})
    void numbersOutsideConfiguredRangeOrNonFiniteAreRejected(double value) {
        FormField field = field(FormFieldType.NUMBER, false); field.setConfig(Map.of("min", 0, "max", 10));
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(2L)).thenReturn(List.of(field));
        assertThatThrownBy(() -> service.resolveVersion(type, 2L, Map.of("answer", value), true)).isInstanceOf(FormValidationException.class);
    }

    @Test void versionOwnershipAndActiveTypeAreRecheckedButOldInactiveVersionRemainsValid() {
        RequestType other = new RequestType(); other.setId(3L); other.setActive(true);
        template.setActive(false);
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(2L)).thenReturn(List.of());
        assertThat(service.resolveVersion(type, 2L, Map.of(), true).formTemplateId()).isEqualTo(2L);
        assertThatThrownBy(() -> service.resolveVersion(other, 2L, Map.of(), true)).isInstanceOf(FormValidationException.class);
        type.setActive(false);
        assertThatThrownBy(() -> service.resolveVersion(type, 2L, Map.of(), true)).isInstanceOf(FormValidationException.class);
    }

    private FormField field(FormFieldType kind, boolean unknown) {
        FormField f = new FormField(); f.setCode("answer"); f.setLabel("Respuesta"); f.setType(kind);
        f.setRequired(true); f.setAllowUnknown(unknown); return f;
    }
}
