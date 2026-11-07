package com.reclamos.backend.config;

import com.reclamos.backend.service.TrackingCodeCipher;
import com.reclamos.backend.service.TrackingCodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class TrackingEncryptionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TrackingEncryptionConfiguration.class, TrackingCodeService.class);
    private static final String TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void startsOnlyWithExplicitValidConfiguration() {
        runner.withPropertyValues("app.tracking.encryption.key-base64=" + TEST_KEY,
                "app.tracking.encryption.key-id=test-v1").run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(TrackingCodeCipher.class));
    }

    @Test
    void missingConfigurationFailsClosed() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.tracking.encryption.key-base64=" + TEST_KEY)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.tracking.encryption.key-id=test-v1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidConfigurationDoesNotPrintRejectedSecret(CapturedOutput output) {
        String rejected = "private-rejected-value-must-never-appear";
        runner.withPropertyValues("app.tracking.encryption.key-base64=" + rejected,
                "app.tracking.encryption.key-id=test-v1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("TRACKING_CODE_ENCRYPTION_KEY_BASE64");
            var trace = new java.io.StringWriter();
            context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain(rejected);
        });
        runner.withPropertyValues("app.tracking.encryption.key-base64=" + TEST_KEY,
                "app.tracking.encryption.key-id=invalid|id").run(context -> assertThat(context).hasFailed());
        assertThat(output).doesNotContain(rejected, TEST_KEY);
    }
}
