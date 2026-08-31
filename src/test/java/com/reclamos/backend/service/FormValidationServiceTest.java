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
        template = new FormTemplate();
        template.setId(3L);
        when(templateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(17L))
                .thenReturn(Optional.of(template));
    }

    @Test
    void acceptsValidFormAndMissingOptionalField() {
        configureFields(
                field("description", "Descripción", FormFieldType.TEXT, true, Map.of()),
                field("additional", "Referencia adicional", FormFieldType.TEXT, false, Map.of()),
                field("danger", "Peligro", FormFieldType.BOOLEAN, true, Map.of()),
                field("amount", "Cantidad", FormFieldType.NUMBER, true, Map.of())
        );

        assertDoesNotThrow(() -> service.validate(requestType,
                Map.of("description", "Frente a la plaza", "danger", true, "amount", 2.5)));
    }

    @Test
    void rejectsMissingRequiredField() {
        configureFields(field("description", "Descripción", FormFieldType.TEXT, true, Map.of()));
        assertThrows(FormValidationException.class, () -> service.validate(requestType, Map.of()));
    }

    @Test
    void rejectsNumberForText() {
        configureFields(field("reference", "Referencia", FormFieldType.TEXT, false, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("reference", 123)));
    }

    @Test
    void rejectsStringForBoolean() {
        configureFields(field("danger", "Peligro", FormFieldType.BOOLEAN, false, Map.of()));
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
        configureFields(field("reference", "Referencia", FormFieldType.TEXT, false, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("invented", "value")));
    }

    @Test
    void acceptsIsoDate() {
        configureFields(field("date", "Fecha", FormFieldType.DATE, true, Map.of()));
        assertDoesNotThrow(() -> service.validate(requestType, Map.of("date", "2026-08-30")));
    }

    @Test
    void rejectsInvalidDate() {
        configureFields(field("date", "Fecha", FormFieldType.DATE, true, Map.of()));
        assertThrows(FormValidationException.class,
                () -> service.validate(requestType, Map.of("date", "30/08/2026")));
    }

    private FormField selectField() {
        Map<String, Object> config = new HashMap<>();
        config.put("options", List.of(
                Map.of("value", "BROKEN_TILES", "label", "Baldosas rotas"),
                Map.of("value", "SINKING", "label", "Hundimiento")
        ));
        return field("damageType", "Tipo de daño", FormFieldType.SELECT, true, config);
    }

    private FormField field(String code, String label, FormFieldType type,
                            boolean required, Map<String, Object> config) {
        FormField field = new FormField();
        field.setCode(code);
        field.setLabel(label);
        field.setType(type);
        field.setRequired(required);
        field.setConfig(new HashMap<>(config));
        return field;
    }

    private void configureFields(FormField... fields) {
        when(fieldRepository.findAllByFormTemplate_IdOrderByDisplayOrderAsc(3L))
                .thenReturn(List.of(fields));
    }
}