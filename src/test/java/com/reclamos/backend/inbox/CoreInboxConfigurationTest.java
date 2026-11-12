package com.reclamos.backend.inbox;

import com.rabbitmq.client.ConnectionFactory;
import com.reclamos.backend.outbox.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreInboxConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(CoreOutboxSettings.class,CoreRabbitConfiguration.class,CoreEventConsumer.class,RabbitCoreEventPublisher.class)
            .withBean(CoreInboxProcessor.class,() -> mock(CoreInboxProcessor.class));
    private ApplicationContextRunner credentials() {
        return context.withPropertyValues("app.core.outbox.host=rabbit.invalid","app.core.outbox.port=5671",
                "app.core.outbox.virtual-host=core","app.core.outbox.username=synthetic","app.core.outbox.password=synthetic",
                "app.core.outbox.approved-event-ids=","app.core.outbox.max-attempts=3","app.core.outbox.retry-delay=15s",
                "app.core.outbox.confirm-timeout=2s","app.core.outbox.connect-timeout=2s");
    }
    @Test void bothDisabledRequireNoCredentialsAndCreateNoNetworkClient() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(CoreEventConsumer.class).doesNotHaveBean(ConnectionFactory.class)
                .doesNotHaveBean(CoreEventPublisher.class));
    }
    @Test void consumerOnlyReusesTlsFactoryWithoutStartingPublication() {
        credentials().withPropertyValues("app.core.inbox.enabled=true","app.core.outbox.enabled=false").run(ctx -> {
            assertThat(ctx).hasSingleBean(CoreEventConsumer.class).hasSingleBean(ConnectionFactory.class).doesNotHaveBean(CoreEventPublisher.class);
            assertThat(ctx.getBean(ConnectionFactory.class).isSSL()).isTrue();
            assertThat(ctx.getBean(ConnectionFactory.class).isTopologyRecoveryEnabled()).isFalse();
        });
    }
    @Test void publicationOnlyCreatesNoConsumerAndBothCanBeEnabledIndependently() {
        credentials().withPropertyValues("app.core.outbox.enabled=true","app.core.inbox.enabled=false").run(ctx ->
                assertThat(ctx).hasSingleBean(CoreEventPublisher.class).doesNotHaveBean(CoreEventConsumer.class));
        credentials().withPropertyValues("app.core.outbox.enabled=true","app.core.inbox.enabled=true").run(ctx ->
                assertThat(ctx).hasSingleBean(CoreEventPublisher.class).hasSingleBean(CoreEventConsumer.class).hasSingleBean(ConnectionFactory.class));
    }
}
