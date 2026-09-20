package com.reclamos.backend.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ticket.duplicate-search")
public class DuplicateSearchProperties {
    @DecimalMin(value = "0", inclusive = false)
    private double radiusMeters;

    @NotNull
    private Duration timeWindow;

    @AssertTrue(message = "ticket.duplicate-search.time-window debe ser mayor que cero")
    public boolean isTimeWindowPositive() {
        return timeWindow != null && !timeWindow.isZero() && !timeWindow.isNegative();
    }
}