package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class OutgoingEventEnvelopeTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void directProcessSharesCorrelationWithoutInventingCausationAndNextProcessGetsNewCorrelation() {
        TransactionSynchronizationManager.initSynchronization();
        var first = event();
        var second = event();
        EventEnvelopeContractAssertions.assertValid(first);
        assertThat(UUID.fromString((String) first.get("eventId")).version()).isEqualTo(4);
        assertThat(UUID.fromString((String) first.get("correlationId")).version()).isEqualTo(4);
        assertThat(first.get("eventId")).isNotEqualTo(second.get("eventId"));
        assertThat(first.get("correlationId")).isEqualTo(second.get("correlationId"));
        assertThat(first.get("causationId")).isNull();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        assertThat(event().get("correlationId")).isNotEqualTo(first.get("correlationId"));
    }

    @Test
    void incomingTraceIsCopiedAndRestoredEvenWhenProcessingFails() {
        TransactionSynchronizationManager.initSynchronization();
        var direct = event();
        UUID correlation = UUID.randomUUID();
        UUID cause = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (var ignored = OutgoingEventEnvelope.incoming(correlation, cause)) {
                var outgoing = event();
                EventEnvelopeContractAssertions.assertValid(outgoing);
                assertThat(outgoing).containsEntry("correlationId", correlation.toString())
                        .containsEntry("causationId", cause.toString());
                assertThat(outgoing.get("eventId")).isNotEqualTo(cause.toString());
                throw new IllegalStateException("rollback");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(event()).containsEntry("correlationId", direct.get("correlationId"))
                .containsEntry("causationId", null);
    }

    @Test
    void missingIncomingCorrelationRemainsNull() {
        TransactionSynchronizationManager.initSynchronization();
        UUID cause = UUID.randomUUID();
        try (var ignored = OutgoingEventEnvelope.incoming(null, cause)) {
            var outgoing = event();
            EventEnvelopeContractAssertions.assertValid(outgoing);
            assertThat(outgoing).containsEntry("correlationId", null).containsEntry("causationId", cause.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"specVersion", "producer", "subject", "receivedAt", "unexpected"})
    void originalEnvelopeSchemaRejectsAdditionalFields(String field) {
        var outgoing = event();
        outgoing.put(field, "legacy");
        assertInvalid(outgoing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "occurredAt", "sourceModule", "data"})
    void originalEnvelopeSchemaRejectsMissingRequiredFields(String field) {
        var outgoing = event();
        outgoing.remove(field);
        assertInvalid(outgoing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "eventVersion", "occurredAt", "sourceModule", "correlationId", "causationId", "data"})
    void originalEnvelopeSchemaRejectsIncorrectTypes(String field) {
        var outgoing = event();
        outgoing.put(field, 123);
        assertInvalid(outgoing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "correlationId", "causationId", "occurredAt"})
    void originalEnvelopeSchemaRejectsMalformedIdsAndDates(String field) {
        var outgoing = event();
        outgoing.put(field, field.equals("occurredAt") ? "2026-02-30T12:00:00Z" : "invalid-uuid");
        assertInvalid(outgoing);
    }

    private void assertInvalid(Map<String, Object> outgoing) {
        assertThat(EventEnvelopeContractAssertions.schema("envelope.schema.json")
                .validate(json.valueToTree(outgoing))).isNotEmpty();
    }

    private Map<String, Object> event() {
        return OutgoingEventEnvelope.create(UUID.randomUUID(), "ticketUpdated", NOW, Map.of());
    }
}
