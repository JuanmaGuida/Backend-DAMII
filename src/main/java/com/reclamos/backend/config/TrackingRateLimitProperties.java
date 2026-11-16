package com.reclamos.backend.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Umbrales del límite de intentos fallidos sobre los endpoints públicos de
 * tracking (DDA2-197). Los intentos se cuentan en una ventana deslizante: al
 * alcanzar el máximo, el cliente o el ticket quedan bloqueados hasta que el
 * intento más antiguo salga de la ventana.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "tracking.rate-limit")
public class TrackingRateLimitProperties {
    /** Fallos tolerados por dirección de cliente dentro de la ventana. */
    @Min(1)
    private int maxFailuresPerClient;

    /** Fallos tolerados por ticket existente, sin importar desde qué cliente. */
    @Min(1)
    private int maxFailuresPerTicket;

    @NotNull
    private Duration window;

    @AssertTrue(message = "tracking.rate-limit.window debe ser mayor que cero")
    public boolean isWindowPositive() {
        return window != null && !window.isZero() && !window.isNegative();
    }
}
