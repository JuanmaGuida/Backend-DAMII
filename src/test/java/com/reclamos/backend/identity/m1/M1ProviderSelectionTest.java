package com.reclamos.backend.identity.m1;

import com.reclamos.backend.config.TimeConfiguration;
import com.reclamos.backend.identity.IdentityProvider;
import com.reclamos.backend.identity.mock.DevModuleUserInitializer;
import com.reclamos.backend.identity.mock.MockIdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;

import static org.assertj.core.api.Assertions.*;

class M1ProviderSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().setActiveProfiles("dev");
                context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            })
            .withUserConfiguration(M1Settings.class, M1AccessTokenVerifier.class, M1HttpConfiguration.class,
                    M1IdentityProvider.class, MockIdentityProvider.class, TimeConfiguration.class);

    @Test
    void absentSelectionKeepsMockWithoutRequiringAnM1Secret() {
        runner.withPropertyValues("app.identity.mock.session-ttl=PT8H",
                "app.identity.mock.citizen-password=synthetic", "app.identity.mock.agent-password=synthetic",
                "app.identity.mock.area-responsible-password=synthetic", "app.identity.mock.admin-password=synthetic")
                .run(context -> {
                    assertThat(context).hasSingleBean(IdentityProvider.class).hasSingleBean(MockIdentityProvider.class);
                    assertThat(context).doesNotHaveBean(M1IdentityProvider.class).doesNotHaveBean(M1Settings.class);
                });
    }

    @Test
    void explicitM1SelectionDisablesMockAndItsInitializerEvenUnderDevProfile() {
        configured(M1TestTokens.encodedKey()).withUserConfiguration(DevModuleUserInitializer.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(IdentityProvider.class).hasSingleBean(M1IdentityProvider.class);
                    assertThat(context).doesNotHaveBean(MockIdentityProvider.class).doesNotHaveBean(DevModuleUserInitializer.class);
                });
    }

    @Test
    void invalidSecretFailsClosedWithoutFallbackOrSecretInFailure() {
        configured("private-not-base64").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-not-base64");
        });
    }

    private ApplicationContextRunner configured(String secret) {
        return runner.withPropertyValues("app.identity.provider=M1", "app.identity.m1.base-url=" + M1TestTokens.BASE,
                "app.identity.m1.jwt-secret-base64=" + secret, "app.identity.m1.browser-origin=" + M1TestTokens.ORIGIN,
                "app.identity.m1.connect-timeout=1s", "app.identity.m1.read-timeout=2s");
    }
}
