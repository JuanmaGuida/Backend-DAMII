package com.reclamos.backend.outbox;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.nio.NioParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.GeneralSecurityException;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${app.core.outbox.enabled:false} or ${app.core.inbox.enabled:false}")
public class CoreRabbitConfiguration {
    @Bean("coreOutboxConnectionFactory")
    ConnectionFactory coreOutboxConnectionFactory(CoreOutboxSettings settings) throws GeneralSecurityException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(settings.host()); factory.setPort(settings.port());
        factory.setVirtualHost(settings.virtualHost());
        factory.setUsername(settings.username()); factory.setPassword(settings.password());
        // null trust managers load the JVM trust store. Never use Rabbit's trust-all overload.
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, null, null);
        factory.useSslProtocol(tls);
        // Bound outbound queuing as well as confirm waiting, even under broker backpressure.
        factory.useNio();
        factory.setNioParams(new NioParams().setWriteEnqueuingTimeoutInMs(100).setWriteQueueCapacity(64));
        factory.enableHostnameVerification();
        factory.setConnectionTimeout(Math.toIntExact(settings.connectTimeout().toMillis()));
        factory.setHandshakeTimeout(Math.toIntExact(settings.connectTimeout().toMillis()));
        factory.setChannelRpcTimeout(Math.toIntExact(settings.confirmTimeout().toMillis()));
        factory.setShutdownTimeout(1000);
        factory.setRequestedHeartbeat(10);
        // Retry belongs to the durable Outbox, not an invisible client replay buffer.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        return factory;
    }
}
