package com.reclamos.backend.inbox;

import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.core.inbox.enabled", havingValue = "true")
public final class CoreEventConsumer {
    public static final String QUEUE = "q.atencion-ciudadana";
    private static final Logger LOG = LoggerFactory.getLogger(CoreEventConsumer.class);
    private final ConnectionFactory factory;
    private final CoreInboxProcessor processor;
    private final int prefetch;
    private Connection connection;
    private Channel channel;
    private boolean closed;

    public CoreEventConsumer(@Qualifier("coreOutboxConnectionFactory") ConnectionFactory factory,
                             CoreInboxProcessor processor, @Value("${app.core.inbox.prefetch:1}") int prefetch) {
        if (prefetch < 1 || prefetch > 16) throw new IllegalStateException("Core Inbox prefetch must be between 1 and 16");
        this.factory = factory; this.processor = processor; this.prefetch = prefetch;
    }

    @Scheduled(fixedDelayString = "${app.core.inbox.reconnect-delay:5000}", initialDelayString = "${app.core.inbox.reconnect-delay:5000}")
    public synchronized void connectIfNeeded() {
        if (closed || (connection != null && connection.isOpen() && channel != null && channel.isOpen())) return;
        abort(connection); connection = null; channel = null;
        Connection opened = null;
        try {
            opened = factory.newConnection("m2-core-inbox");
            Channel owner = opened.createChannel();
            owner.queueDeclarePassive(QUEUE);
            owner.basicQos(prefetch);
            Connection owningConnection = opened;
            owner.basicConsume(QUEUE, false, (tag, delivery) -> receive(owner, owningConnection, delivery),
                    tag -> abort(owningConnection));
            connection = opened; channel = owner;
        } catch (Exception unavailable) {
            abort(opened);
            LOG.warn("Core Inbox code=CONNECTION_UNAVAILABLE");
        }
    }

    void receive(Channel owner, Connection owningConnection, Delivery delivery) {
        boolean processed = false;
        try {
            processor.process(delivery.getBody());
            processed = true; // Includes the DB commit, not just the business method's return.
        } catch (com.reclamos.backend.exception.InvalidTicketRequestException
                 | com.reclamos.backend.exception.TicketStateConflictException rejected) {
            // The transaction has rolled back before the proxy propagates this permanent business rejection.
            // No message content or exception text is safe to log.
            LOG.warn("Core Inbox code=REJECTED_BUSINESS_RULE");
            try {
                processor.recordBusinessRejection(delivery.getBody());
                processed = true; // ACK only after the separate rejection audit commits.
            } catch (RuntimeException auditFailed) {
                LOG.warn("Core Inbox code=REJECTION_AUDIT_FAILED");
            }
        } catch (IncomingEventFailure rejected) {
            LOG.warn("Core Inbox code={}", rejected.code());
        } catch (RuntimeException failed) {
            LOG.warn("Core Inbox code=PROCESSING_FAILED");
        }
        try {
            if (processed) owner.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            else owner.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
        } catch (Exception unavailable) {
            abort(owningConnection); // Redelivery is safe through Inbox; never ACK on a replacement channel.
            LOG.warn("Core Inbox code=ACK_CHANNEL_UNAVAILABLE");
        }
    }
    @PreDestroy
    public synchronized void close() { closed = true; abort(connection); connection = null; channel = null; }
    private static void abort(Connection value) {
        if (value != null) { try { value.abort(1000); } catch (RuntimeException ignored) { } }
    }
}
