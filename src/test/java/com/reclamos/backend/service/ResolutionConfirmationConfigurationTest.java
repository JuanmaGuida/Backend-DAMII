package com.reclamos.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResolutionConfirmationConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultConfirmationDurationIsThreeDays() {
        contextRunner.run(context -> {
            Duration confirmationDuration = Binder.get(context.getEnvironment())
                    .bind("ticket.resolution.confirmation-duration", Duration.class)
                    .orElseThrow(() -> new AssertionError("Falta la configuración de confirmación"));

            assertEquals(Duration.ofDays(3), confirmationDuration);
        });
    }
}
