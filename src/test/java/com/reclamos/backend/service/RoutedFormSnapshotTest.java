package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.FormValidationException;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutedFormSnapshotTest {
    private final FormTemplateRepository templates = mock(FormTemplateRepository.class);
    private final FormFieldRepository fields = mock(FormFieldRepository.class);
    private final FormValidationService service = new FormValidationService(templates, fields);

    @Test
    void usesConfirmedVersionAndCopiesOnlyAnswersWithoutInternalOrPreviousClassificationKeys() {
        Ticket ticket = ticket();
        FormField field = field(FormFieldType.NUMBER);
        FormField unknown = field(FormFieldType.BOOLEAN);
        unknown.setCode("unknown");
        unknown.setAllowUnknown(true);
        field.setConfig(Map.of("riskScore", 4, "formFieldId", 987));
        when(templates.existsByIdAndRequestType_Id(7L, 12L)).thenReturn(true);
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(7L)).thenReturn(List.of(field, unknown));
        var answers = new LinkedHashMap<String, Object>();
        answers.put("answer", 3);
        answers.put("unknown", null);
        answers.put("oldAnswer", "outdated");
        answers.put("formTemplateId", 888);
        answers.put("trackingCodeHash", "private");
        answers.put("internal", Map.of("token", "secret"));
        ticket.setFormData(answers);

        var snapshot = service.snapshotAnswers(ticket);

        assertThat(snapshot).containsOnlyKeys("answer", "unknown")
                .containsEntry("answer", 3).containsEntry("unknown", null);
        answers.put("answer", 99);
        assertThat(snapshot.get("answer")).isEqualTo(3);
        verify(templates, never()).findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(any());
    }

    @Test
    void omitsAnswersWithoutConfirmedTemplateAndAcceptsAbsentOrEmptyAnswers() {
        Ticket ticket = ticket();
        ticket.setFormData(null);
        assertThat(service.snapshotAnswers(ticket)).isEmpty();
        ticket.setFormData(Map.of());
        assertThat(service.snapshotAnswers(ticket)).isEmpty();
        ticket.setFormTemplateId(null);
        ticket.setFormData(Map.of("formTemplateId", 99));
        assertThat(service.snapshotAnswers(ticket)).isEmpty();
        verifyNoInteractions(templates, fields);
    }

    @Test
    void rejectsTemplateFromAnotherClassification() {
        Ticket ticket = ticket();
        ticket.setFormData(Map.of("answer", true));
        assertThatThrownBy(() -> service.snapshotAnswers(ticket)).isInstanceOf(FormValidationException.class);
        verifyNoInteractions(fields);
    }

    @Test
    void rejectsUnknownNullForAFieldThatDoesNotAllowIt() {
        Ticket ticket = ticket();
        var answers = new LinkedHashMap<String, Object>();
        answers.put("answer", null);
        ticket.setFormData(answers);
        when(templates.existsByIdAndRequestType_Id(7L, 12L)).thenReturn(true);
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(7L))
                .thenReturn(List.of(field(FormFieldType.BOOLEAN)));
        assertThatThrownBy(() -> service.snapshotAnswers(ticket)).isInstanceOf(FormValidationException.class);
    }

    @ParameterizedTest
    @EnumSource(FormFieldType.class)
    void rejectsNestedObjectsInScalarAnswers(FormFieldType type) {
        Ticket ticket = ticket();
        ticket.setFormData(Map.of("answer", Map.of("token", "must-not-leak")));
        when(templates.existsByIdAndRequestType_Id(7L, 12L)).thenReturn(true);
        when(fields.findAllByFormTemplate_IdOrderByDisplayOrderAsc(7L)).thenReturn(List.of(field(type)));
        assertThatThrownBy(() -> service.snapshotAnswers(ticket)).isInstanceOf(FormValidationException.class);
    }

    private Ticket ticket() {
        RequestType type = new RequestType();
        type.setId(12L);
        Ticket ticket = new Ticket();
        ticket.setRequestType(type);
        ticket.setFormTemplateId(7L);
        return ticket;
    }

    private FormField field(FormFieldType type) {
        FormField field = new FormField();
        field.setCode("answer");
        field.setType(type);
        return field;
    }
}
