package com.reclamos.backend.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuración de las funciones asistidas por IA. Todo arranca apagado: sin
 * {@code app.ai.enabled=true} y una clave, ningún texto sale hacia el proveedor
 * y las pantallas usan su comportamiento sin IA.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {
    private boolean enabled = false;
    private String apiKey = "";
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private String model = "gemini-3.5-flash-lite";
    private Duration timeout = Duration.ofSeconds(5);

    /** Tope de caracteres del texto del usuario que se envía al proveedor. */
    private int maxInputChars = 2000;

    /** Pedidos por cliente dentro de {@link #rateLimitWindow}. */
    private int maxRequestsPerClient = 10;
    private Duration rateLimitWindow = Duration.ofMinutes(10);

    /** Tope global por minuto: protege la cuota gratuita del proveedor. */
    private int maxRequestsPerMinute = 10;

    /** Fallos seguidos del proveedor que abren el corte y cuánto dura. */
    private int circuitFailureThreshold = 3;
    private Duration circuitOpenFor = Duration.ofSeconds(60);

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
