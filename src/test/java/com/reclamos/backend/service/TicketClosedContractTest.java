package com.reclamos.backend.service;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.repository.OutboxEventRepository;
import com.reclamos.backend.repository.AttachmentRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TicketClosedContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"CITIZEN_CONFIRMED", "CONFIRMATION_TIMEOUT", "ADMINISTRATIVE_CLOSE"})
    @SuppressWarnings("unchecked")
    void closedDataConformsToUnmodifiedV177SchemaAndRejectsOldKeyOrUnknownCode(String reasonCode) {
        var repository = mock(OutboxEventRepository.class);
        var now = Instant.parse("2026-10-20T12:00:00Z");
        var service = new TicketOutboxService(repository, Clock.fixed(now, ZoneOffset.UTC),
                mock(AttachmentReferenceService.class),
                mock(AttachmentRepository.class), mock(FormValidationService.class));
        var ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setPublicId("TK-2026-000123");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setResponsibleAreaId("M6");
        ticket.setCurrentStatus(TicketStatus.CLOSED);
        ticket.setCurrentPriority(Priority.MEDIUM);

        service.closed(ticket, reasonCode, now);

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        var data = new LinkedHashMap<>((Map<String, Object>) captor.getValue().getPayload().get("data"));
        assertThat(data.get("details")).isEqualTo(Map.of("closure", Map.of("reasonCode", reasonCode)));
        EventDataContractAssertions.assertValid(data);
        EventEnvelopeContractAssertions.assertValid(captor.getValue().getPayload());
        EventEnvelopeContractAssertions.assertFullEventValid(captor.getValue().getPayload());
        var wrongSource = new LinkedHashMap<>(captor.getValue().getPayload());
        wrongSource.put("sourceModule", "M2");
        EventEnvelopeContractAssertions.assertFullEventInvalid(wrongSource);
        var wrongVersion = new LinkedHashMap<>(captor.getValue().getPayload());
        wrongVersion.put("eventVersion", "2.0");
        EventEnvelopeContractAssertions.assertFullEventInvalid(wrongVersion);
        data.put("details", Map.of("closure", Map.of("reason", reasonCode)));
        EventDataContractAssertions.assertInvalid(data);
        data.put("details", Map.of("closure", Map.of("reasonCode", "UNKNOWN")));
        EventDataContractAssertions.assertInvalid(data);
    }
}
