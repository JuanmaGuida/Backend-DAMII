package com.reclamos.backend.outbox;

import java.util.UUID;

public interface CoreEventPublisher {
    /** Success means broker confirm without mandatory return, never Core processing. */
    void publish(UUID eventId, String eventType, byte[] body);
}
