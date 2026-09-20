package com.reclamos.backend.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateSearchPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DuplicateSearchProperties.class);

    @Test
    void registersBeanAndBindsDefaultsFromApplicationProperties() {
        contextRunner.run(context -> {
            assertTrue(context.containsBean("duplicateSearchProperties"));
            DuplicateSearchProperties properties = context.getBean(DuplicateSearchProperties.class);
            assertEquals(500, properties.getRadiusMeters());
            assertEquals(Duration.ofHours(24), properties.getTimeWindow());
        });
    }

    @Test
    void rejectsNonPositiveRadiusAndWindow() {
        DuplicateSearchProperties properties = new DuplicateSearchProperties();
        properties.setRadiusMeters(0);
        properties.setTimeWindow(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }
}