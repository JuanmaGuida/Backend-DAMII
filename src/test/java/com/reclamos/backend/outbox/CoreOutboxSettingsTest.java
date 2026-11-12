package com.reclamos.backend.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CoreOutboxSettingsTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(CoreOutboxSettings.class, CoreRabbitConfiguration.class, RabbitCoreEventPublisher.class);

    @Test void disabledByDefaultCreatesNoClientAndRequiresNoSecret() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(CoreOutboxSettings.class)
                .doesNotHaveBean(RabbitCoreEventPublisher.class).doesNotHaveBean("coreOutboxConnectionFactory"));
    }
    @Test void enabledWithEmptyApprovalListIsValidAndDoesNotConnectOnStartup() {
        context.withPropertyValues("app.core.outbox.enabled=true", "app.core.outbox.host=rabbit.example.test",
                "app.core.outbox.port=5671", "app.core.outbox.virtual-host=core", "app.core.outbox.username=synthetic",
                "app.core.outbox.password=synthetic", "app.core.outbox.approved-event-ids=", "app.core.outbox.max-attempts=3",
                "app.core.outbox.retry-delay=15s", "app.core.outbox.confirm-timeout=2s", "app.core.outbox.connect-timeout=2s")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CoreEventPublisher.class);
                    assertThat(ctx.getBean(CoreOutboxSettings.class).approvedEventIds()).isEmpty();
                });
    }
    @Test void validatesExplicitApprovalsAndBoundsWithoutEchoingRejectedValues() {
        var settings = OutboxFixtures.settings(Set.of(UUID.randomUUID()));
        assertThat(settings.approvedEventIds()).hasSize(1);
        assertThat(settings.retryDelay(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.retryDelay(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.retryDelay(20)).isEqualTo(Duration.ofHours(1));
        for (String approved : new String[]{"*", "private-not-uuid", UUID.randomUUID() + ",", "00000000-0000-0000-0000-000000000000"}) {
            assertThatThrownBy(() -> settings("synthetic", approved, 3, Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(approved);
        }
        assertThatThrownBy(() -> settings("", "", 3, Duration.ofSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("synthetic", "", 0, Duration.ofSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings("synthetic", "", 3, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }
    @Test void tlsUsesJvmTrustAndEnforcesHostnameWithoutAutomaticRecovery() throws Exception {
        var settings = OutboxFixtures.settings(Set.of());
        var factory = new CoreRabbitConfiguration().coreOutboxConnectionFactory(settings);
        assertThat(factory.getHost()).isEqualTo("rabbit.example.test");
        assertThat(factory.getVirtualHost()).isEqualTo("core");
        assertThat(factory.getPort()).isEqualTo(5671);
        assertThat(factory.isSSL()).isTrue();
        assertThat(factory.isAutomaticRecoveryEnabled()).isFalse();
        assertThat(factory.isTopologyRecoveryEnabled()).isFalse();
        var engine = SSLContext.getDefault().createSSLEngine("rabbit.example.test", 5671);
        factory.getNioParams().getSslEngineConfigurator().configure(engine);
        assertThat(engine.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(factory.getNioParams().getWriteEnqueuingTimeoutInMs()).isEqualTo(100);
    }
    private CoreOutboxSettings settings(String password, String approved, int attempts, Duration timeout) {
        return new CoreOutboxSettings("rabbit.example.test", 5671, "core", "synthetic", password, approved,
                attempts, Duration.ofSeconds(15), timeout, Duration.ofSeconds(2));
    }
}
