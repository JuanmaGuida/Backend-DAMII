package com.reclamos.backend.outbox;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.io.IOException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
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
        } catch (Exception failure) { throw new PublicationFailure(classify(failure)); }
        finally {
            // Dedicated connection: close every channel with a bounded abort. Cleanup cannot undo a confirm.
            if (connection != null) {
                try { connection.abort(1000); }
                catch (RuntimeException ignored) { /* No credentials or broker exception text in logs. */ }
            }
        }
    }

    private static PublicationFailure.Code classify(Exception failure) {
        // Client IOExceptions can wrap a permanent AMQP channel/connection rejection.
        // Inspect typed causes only; never retain or log the broker's private text.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof AuthenticationFailureException || cause instanceof SSLHandshakeException
                    || cause instanceof SSLPeerUnverifiedException || cause instanceof SSLProtocolException)
                return PUBLICATION_ERROR;
            if (cause instanceof ShutdownSignalException shutdown) {
                Integer reply = shutdown.getReason() instanceof AMQP.Channel.Close channel ? channel.getReplyCode()
                        : shutdown.getReason() instanceof AMQP.Connection.Close connection ? connection.getReplyCode() : null;
                // Forced shutdown and broker internal errors can recover. Protocol/access/topology
                // rejections require a correction and retain the existing bounded attempt policy.
                if (reply != null && reply != AMQP.CONNECTION_FORCED && reply != AMQP.INTERNAL_ERROR)
                    return PUBLICATION_ERROR;
            }
        }
        return failure instanceof IOException || failure instanceof ShutdownSignalException
                ? BROKER_UNAVAILABLE : PUBLICATION_ERROR;
    }
}
