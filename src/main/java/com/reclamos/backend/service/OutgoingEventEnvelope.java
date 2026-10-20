package com.reclamos.backend.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Core/Eventos v1.77 producer envelope. The stored Outbox payload is the delivery snapshot. */
final class OutgoingEventEnvelope {
    static Map<String, Object> create(UUID eventId, String eventType, Instant occurredAt,
                                      Map<String, Object> data) {
        Trace trace = context().trace;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", "1.0");
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("sourceModule", "atencion-ciudadana");
        envelope.put("correlationId", string(trace.correlationId()));
        envelope.put("causationId", string(trace.causationId()));
        envelope.put("data", data);
        return envelope;
    }

    /**
     * Scope for independent M2 facts discovered while processing an incoming event.
     * Null correlation is preserved when the legacy simulator supplies none.
     * A future Core adapter must supply the received correlationId and eventId here,
     * never the incoming causationId. Production callers execute inside a transaction.
     */
    static IncomingScope incoming(UUID correlationId, UUID eventId) {
        TraceSynchronization context = context();
        Trace previous = context.trace;
        context.trace = new Trace(correlationId, eventId);
        return () -> context.trace = previous;
    }

    private static TraceSynchronization context() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof TraceSynchronization trace) {
                    return trace;
                }
            }
            TraceSynchronization trace = new TraceSynchronization();
            TransactionSynchronizationManager.registerSynchronization(trace);
            return trace;
        }
        // Standalone construction (unit tests): one direct process per envelope.
        return new TraceSynchronization();
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    interface IncomingScope extends AutoCloseable {
        @Override
        void close();
    }

    private record Trace(UUID correlationId, UUID causationId) { }

    /** Spring scopes this to the transaction, including suspend/resume and rollback. */
    private static final class TraceSynchronization implements TransactionSynchronization {
        private Trace trace = new Trace(UUID.randomUUID(), null);
    }

    private OutgoingEventEnvelope() { }
}
