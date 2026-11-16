package com.reclamos.backend.outbox;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.reclamos.backend.outbox.PublicationFailure.Code.*;

@Component
@ConditionalOnProperty(name = "app.core.outbox.enabled", havingValue = "true")
public final class RabbitCoreEventPublisher implements CoreEventPublisher {
    public static final String EXCHANGE = "muni.inbox";
    private final ConnectionFactory connections;
    private final CoreOutboxSettings settings;

    public RabbitCoreEventPublisher(@Qualifier("coreOutboxConnectionFactory") ConnectionFactory connections,
                                   CoreOutboxSettings settings) {
        this.connections = connections; this.settings = settings;
    }

    @Override
    public void publish(UUID eventId, String eventType, byte[] body) {
        // One bounded, isolated channel per approved event: no shared channel sequence races.
        Connection connection = null;
        try {
            try { connection = connections.newConnection("m2-core-outbox"); }
            catch (TimeoutException unavailable) { throw new PublicationFailure(BROKER_UNAVAILABLE); }
            Channel channel = connection.createChannel();
            channel.exchangeDeclarePassive(EXCHANGE); // Never create or change shared topology.
            channel.confirmSelect();
            AtomicBoolean returned = new AtomicBoolean(false);
            channel.addReturnListener(message -> returned.set(true));
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json").contentEncoding("UTF-8").deliveryMode(2)
                    .messageId(eventId.toString()).type(eventType).appId("atencion-ciudadana")
                    .userId(settings.username()).build();
            channel.basicPublish(EXCHANGE, "", true, properties, body);
            boolean confirmed = channel.waitForConfirms(settings.confirmTimeout().toMillis());
            if (returned.get()) throw new PublicationFailure(UNROUTABLE);
            if (!confirmed) throw new PublicationFailure(BROKER_NACK);
        } catch (PublicationFailure failure) { throw failure; }
        catch (TimeoutException timeout) { throw new PublicationFailure(CONFIRM_TIMEOUT); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new PublicationFailure(INTERRUPTED);
        } catch (Exception unavailable) { throw new PublicationFailure(BROKER_UNAVAILABLE); }
        finally {
            // Dedicated connection: close every channel with a bounded abort. Cleanup cannot undo a confirm.
            if (connection != null) {
                try { connection.abort(1000); }
                catch (RuntimeException ignored) { /* No credentials or broker exception text in logs. */ }
            }
        }
    }
}
