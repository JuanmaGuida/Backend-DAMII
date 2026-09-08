package com.reclamos.backend.service;

import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormValidationServiceTest {
    @Mock
    private FormTemplateRepository templateRepository;
    @Mock
    private FormFieldRepository fieldRepository;

    private FormValidationService service;
    private RequestType requestType;
    private FormTemplate template;

    @BeforeEach
    void setUp() {
        service = new FormValidationService(templateRepository, fieldRepository);
        requestType = new RequestType();
        requestType.setId(17L);
        requestType.setActive(true);
        template = new FormTemplate();
        template.setId(3L);
        when(templateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(17L))
                .thenReturn(Optional.of(template));
    }

    @Test
    void acceptsValidPartialFormAndAllFieldsMayBeOmitted() {
        configureFields(
                field("description", "Descripción", FormFieldType.TEXT, Map.of()),
                field("additional", "Referencia adicional", FormFieldType.TEXT, Map.of()),
                field("danger", "Peligro", FormFieldType.BOOLEAN, Map.of()),
                field("amount", "Cantidad", FormFieldType.NUMBER, Map.of())
        );

        assertDoesNotThrow(() -> service.validate(requestType,
                Map.of("description", "Frente a la plaza", "danger", true, "amount", 2.5)));
        assertDoesNotThrow(() -> service.validate(requestType, Map.of()));
    }

    @Test
    void rejectsNumberForText() {
        configureFields(field("reference", "Referencia", FormFieldType.TEXT, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("reference", 123)));
    }

    @Test
    void rejectsStringForBoolean() {
        configureFields(field("danger", "Peligro", FormFieldType.BOOLEAN, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("danger", "true")));
    }

    @Test
    void acceptsConfiguredSelectOption() {
        configureFields(selectField());
        assertDoesNotThrow(() -> service.validate(requestType, Map.of("damageType", "SINKING")));
    }

    @Test
    void rejectsUnknownSelectOption() {
        configureFields(selectField());
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("damageType", "OTHER")));
    }

    @Test
    void rejectsUnknownField() {
        configureFields(field("reference", "Referencia", FormFieldType.TEXT, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("invented", "value")));
    }

    @Test
    void rejectsOmittedRequiredField() {
        FormField required = field("danger", "Peligro", FormFieldType.BOOLEAN, Map.of());
        required.setRequired(true);
        configureFields(required);

        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of()));
    }

    @Test
    void requiredFieldAcceptsExplicitUnknownWhenConfigured() {
        FormField required = field("danger", "Peligro", FormFieldType.BOOLEAN, Map.of());
        required.setRequired(true);
        required.setAllowUnknown(true);
        configureFields(required);
        Map<String, Object> data = new HashMap<>();
        data.put("danger", null);

        ResolvedForm resolved = service.resolveAndValidate(requestType, data);

        assertNull(resolved.formData().get("danger"));
    }

    @Test
    void rejectsExplicitUnknownWhenNotConfigured() {
        configureFields(field("danger", "Peligro", FormFieldType.BOOLEAN, Map.of()));
        Map<String, Object> data = new HashMap<>();
        data.put("danger", null);

        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, data));
    }

    @Test
    void acceptsIsoDate() {
        configureFields(field("date", "Fecha", FormFieldType.DATE, Map.of()));
        assertDoesNotThrow(() -> service.validate(requestType, Map.of("date", "2026-08-30")));
    }

    @Test
    void rejectsInvalidDate() {
        configureFields(field("date", "Fecha", FormFieldType.DATE, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("date", "30/08/2026")));
    }

    @Test
    void preservesFalseZeroAndAllowedNullInValidatedData() {
        FormField comment = field("comment", "Comentario", FormFieldType.TEXT, Map.of());
        comment.setAllowUnknown(true);
        configureFields(
                field("danger", "Peligro", FormFieldType.BOOLEAN, Map.of()),
                field("amount", "Cantidad", FormFieldType.NUMBER, Map.of()),
                comment
        );
        Map<String, Object> data = new HashMap<>();
        data.put("danger", false);
        data.put("amount", 0);
        data.put("comment", null);

        ResolvedForm resolved = service.resolveAndValidate(requestType, data);

        assertEquals(false, resolved.formData().get("danger"));
        assertEquals(0, resolved.formData().get("amount"));
        assertNull(resolved.formData().get("comment"));
    }

    @Test
    void preservesEmptyTextButRejectsEmptySelectValue() {
        configureFields(
                field("comment", "Comentario", FormFieldType.TEXT, Map.of()),
                selectField()
        );

        ResolvedForm resolved = service.resolveAndValidate(requestType, Map.of("comment", ""));

        assertEquals("", resolved.formData().get("comment"));
        assertThrows(FormValidationException.class,
                () -> service.resolveAndValidate(requestType, Map.of("damageType", "")));
    }

    @Test
    void requestTypeWithoutTemplateAcceptsOnlyEmptyData() {
        when(templateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(17L))
                .thenReturn(Optional.empty());

        ResolvedForm resolved = service.resolveAndValidate(requestType, Map.of());

        assertNull(resolved.template());
        assertThrows(FormValidationException.class,
                () -> service.resolveAndValidate(requestType, Map.of("invented", "value")));
    }

    private FormField selectField() {
        Map<String, Object> config = new HashMap<>();
        config.put("options", List.of(
                Map.of("value", "BROKEN_TILES", "label", "Baldosas rotas"),
                Map.of("value", "SINKING", "label", "Hundimiento")
        ));
        return field("damageType", "Tipo de daño", FormFieldType.SELECT, config);
    }

    private FormField field(String code, String label, FormFieldType type,
                            Map<String, Object> config) {
        FormField field = new FormField();
        field.setCode(code);
        field.setLabel(label);
        field.setType(type);
        field.setConfig(new HashMap<>(config));
        return field;
    }

    private void configureFields(FormField... fields) {
        when(fieldRepository.findAllByFormTemplate_IdOrderByDisplayOrderAsc(3L))
                .thenReturn(List.of(fields));
    }
}
