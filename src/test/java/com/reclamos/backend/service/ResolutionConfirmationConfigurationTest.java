package com.reclamos.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("dev")
class ResolutionConfirmationConfigurationTest {
    @Value("${ticket.resolution.confirmation-duration}")
    private Duration confirmationDuration;

    @Test
    void defaultConfirmationDurationIsThreeDays() {
        assertEquals(Duration.ofDays(3), confirmationDuration);
    }
}
