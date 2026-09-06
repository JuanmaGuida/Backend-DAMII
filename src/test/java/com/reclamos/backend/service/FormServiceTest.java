package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.FormDefinitionResponse;
import com.reclamos.backend.dto.response.FormFieldResponse;
import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormServiceTest {
    @Mock
    private RequestTypeRepository requestTypeRepository;
    @Mock
    private FormTemplateRepository templateRepository;
    @Mock
    private FormFieldRepository fieldRepository;

    private FormService service;
    private RequestType requestType;
    private FormTemplate template;

    @BeforeEach
    void setUp() {
        service = new FormService(requestTypeRepository, templateRepository, fieldRepository);
        requestType = new RequestType();
        requestType.setId(17L);
        requestType.setCode("BROKEN_SIDEWALK");
        requestType.setActive(true);
        template = new FormTemplate();
        template.setId(5L);
        template.setVersion(2);
    }

    @Test
    void returnsDefinitionInRepositoryOrderWithoutInternalRiskOrRequiredFields() {
        when(requestTypeRepository.findById(17L)).thenReturn(Optional.of(requestType));
        when(templateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(17L))
                .thenReturn(Optional.of(template));
        FormField first = field("damageType", 1);
        first.setConfig(new HashMap<>(Map.of(
                "placeholder", "Seleccione",
                "options", List.of(Map.of("value", "YES", "label", "Sí")))));
        FormField second = field("reference", 2);
        when(fieldRepository.findAllByFormTemplate_IdOrderByDisplayOrderAsc(5L))
                .thenReturn(List.of(first, second));

        FormDefinitionResponse response = service.getFormForRequestType(17L);

        assertEquals(17L, response.getRequestTypeId());
        assertEquals("BROKEN_SIDEWALK", response.getRequestTypeCode());
        assertEquals(2, response.getVersion());
        assertEquals(List.of("damageType", "reference"),
                response.getFields().stream().map(field -> field.getCode()).toList());
        assertEquals("Seleccione", response.getFields().getFirst().getConfig().get("placeholder"));
        assertTrue(response.getFields().getFirst().getConfig().containsKey("options"));
    }

    @Test
    void rejectsMissingRequestType() {
        when(requestTypeRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getFormForRequestType(99L));
    }

    @Test
    void rejectsInactiveRequestType() {
        requestType.setActive(false);
        when(requestTypeRepository.findById(17L)).thenReturn(Optional.of(requestType));
        assertThrows(ResourceNotFoundException.class, () -> service.getFormForRequestType(17L));
    }

    @Test
    void rejectsRequestTypeWithoutForm() {
        when(requestTypeRepository.findById(17L)).thenReturn(Optional.of(requestType));
        when(templateRepository.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(17L))
                .thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getFormForRequestType(17L));
    }

    @Test
    void publicFieldContractDoesNotExposeRequired() {
        Set<String> fields = Arrays.stream(FormFieldResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(!fields.contains("required"));
    }

    private FormField field(String code, int order) {
        FormField field = new FormField();
        field.setCode(code);
        field.setLabel(code);
        field.setType(FormFieldType.TEXT);
        field.setDisplayOrder(order);
        return field;
    }
}
