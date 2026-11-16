package com.reclamos.backend.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.core.outbox.enabled", havingValue = "true")
public final class CoreOutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(CoreOutboxWorker.class);
    private final CoreOutboxProcessor processor;
    public CoreOutboxWorker(CoreOutboxProcessor processor) { this.processor = processor; }

    @Scheduled(fixedDelayString = "${app.core.outbox.scan-delay}", initialDelayString = "${app.core.outbox.scan-delay}")
    public void publishNext() {
        try { processor.processNextApproved(); }
        catch (RuntimeException failure) {
            // DB/commit failures leave the row recoverable. An ACK before rollback can duplicate delivery.
            LOG.warn("Core Outbox transaction did not complete; approved events remain recoverable");
        }
    }
}
