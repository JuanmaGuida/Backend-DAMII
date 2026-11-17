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
 * Umbrales del límite de intentos fallidos sobre {@code /api/auth/login} y
 * {@code /api/auth/refresh} (DDA2-259, SEG-06). Los intentos se cuentan en una
 * ventana deslizante: al alcanzar el máximo, el cliente o la cuenta quedan
 * bloqueados (429 + Retry-After) hasta que el fallo más antiguo salga de la
 * ventana.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "login.rate-limit")
public class LoginRateLimitProperties {
    /** Fallos tolerados por dirección de cliente dentro de la ventana. */
    @Min(1)
    private int maxFailuresPerClient = 15;

    /** Fallos tolerados por usuario, sin importar desde qué cliente lleguen. */
    @Min(1)
    private int maxFailuresPerUsername = 10;

    @NotNull
    private Duration window = Duration.ofMinutes(15);

    @AssertTrue(message = "login.rate-limit.window debe ser mayor que cero")
    public boolean isWindowPositive() {
        return window != null && !window.isZero() && !window.isNegative();
    }
}
