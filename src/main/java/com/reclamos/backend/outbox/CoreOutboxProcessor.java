package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.OutboxStatus;
import com.reclamos.backend.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@ConditionalOnProperty(name = "app.core.outbox.enabled", havingValue = "true")
public class CoreOutboxProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CoreOutboxProcessor.class);
    private final OutboxEventRepository events;
    private final OutgoingEventValidator validator;
    private final CoreEventPublisher publisher;
    private final CoreOutboxSettings settings;
    private final Clock clock;

    public CoreOutboxProcessor(OutboxEventRepository events, OutgoingEventValidator validator,
                              CoreEventPublisher publisher, CoreOutboxSettings settings, Clock clock) {
        this.events = events; this.validator = validator; this.publisher = publisher; this.settings = settings; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 60)
    public boolean processNextApproved() {
        if (settings.approvedEventIds().isEmpty()) return false;
        var event = events.findNextApprovedForUpdate(settings.approvedEventIds(), clock.instant()).orElse(null);
        if (event == null) return false;
        if (event.getRetryCount() >= settings.maxAttempts()) {
            event.setStatus(OutboxStatus.FAILED);
            event.setNextAttemptAt(null);
            event.setLastFailureCode("ATTEMPTS_EXHAUSTED");
            return true;
        }
        try {
            byte[] body = validator.prepare(event);
            event.setRetryCount(event.getRetryCount() + 1);
            publisher.publish(event.getEventId(), event.getEventType(), body);
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(clock.instant());
            event.setNextAttemptAt(null);
            event.setLastFailureCode(null);
            LOG.info("Core Outbox eventId={} brokerConfirmed=true", event.getEventId());
        } catch (PublicationFailure failure) {
            boolean permanent = failure.code() == PublicationFailure.Code.INVALID_PAYLOAD
                    || failure.code() == PublicationFailure.Code.UNVERIFIED_IDENTITY
                    || event.getRetryCount() >= settings.maxAttempts();
            event.setStatus(permanent ? OutboxStatus.FAILED : OutboxStatus.PENDING);
            event.setPublishedAt(null);
            event.setNextAttemptAt(permanent ? null : clock.instant().plus(settings.retryDelay(event.getRetryCount())));
            event.setLastFailureCode(failure.code().name());
            LOG.warn("Core Outbox eventId={} code={} attempts={} status={}", event.getEventId(), failure.code(),
                    event.getRetryCount(), event.getStatus());
        }
        events.save(event);
        return true;
    }
}
