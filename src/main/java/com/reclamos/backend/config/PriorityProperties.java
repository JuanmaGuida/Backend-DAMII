package com.reclamos.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "ticket.priority")
public class PriorityProperties {
    @NotNull @DecimalMin("0") @DecimalMax("1")
    private BigDecimal wRisk = new BigDecimal("0.60");
    @NotNull @DecimalMin("0") @DecimalMax("1")
    private BigDecimal wAffected = new BigDecimal("0.25");
    @NotNull @DecimalMin("0") @DecimalMax("1")
    private BigDecimal wAge = new BigDecimal("0.15");
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
    private BigDecimal mediumThreshold = new BigDecimal("0.15");
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
    private BigDecimal highThreshold = new BigDecimal("0.30");
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
    private BigDecimal criticalThreshold = new BigDecimal("0.45");
    @NotEmpty
    private List<@NotNull @Valid Band> affectedBands = List.of(new Band(0, "0.00"), new Band(1, "0.25"),
            new Band(50, "0.50"), new Band(500, "0.75"), new Band(5000, "1.00"));
    @Min(1)
    private long scanDelay = 300000;
    @Min(1) @Max(1000)
    private int batchSize = 100;

    @AssertTrue(message = "Los pesos de prioridad deben sumar 1")
    public boolean isWeightSumValid() {
        return wRisk != null && wAffected != null && wAge != null
                && wRisk.add(wAffected).add(wAge).compareTo(BigDecimal.ONE) == 0;
    }

    @AssertTrue(message = "Los umbrales de prioridad deben ser estrictamente crecientes")
    public boolean isThresholdOrderValid() {
        return mediumThreshold != null && highThreshold != null && criticalThreshold != null
                && mediumThreshold.compareTo(highThreshold) < 0
                && highThreshold.compareTo(criticalThreshold) < 0;
    }

    @AssertTrue(message = "Las bandas deben comenzar en 0 y crecer en cantidad y score")
    public boolean isBandOrderValid() {
        if (affectedBands == null || affectedBands.isEmpty() || affectedBands.getFirst() == null
                || affectedBands.getFirst().getMinimumCount() != 0) return false;
        int previousCount = -1;
        BigDecimal previousScore = BigDecimal.ZERO;
        for (Band band : affectedBands) {
            if (band == null || band.score == null || band.minimumCount <= previousCount
                    || band.score.compareTo(previousScore) < 0) return false;
            previousCount = band.minimumCount;
            previousScore = band.score;
        }
        return true;
    }

    @Data
    public static class Band {
        @Min(0) private int minimumCount;
        @NotNull @DecimalMin("0") @DecimalMax("1") private BigDecimal score;
        public Band() { }
        public Band(int minimumCount, String score) {
            this.minimumCount = minimumCount;
            this.score = new BigDecimal(score);
        }
    }
}
