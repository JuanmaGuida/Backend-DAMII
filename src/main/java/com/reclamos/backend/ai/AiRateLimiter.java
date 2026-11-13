package com.reclamos.backend.ai;

import com.reclamos.backend.security.ClientAddressResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita cuántas consultas de IA puede disparar un mismo cliente: cada una
 * consume cuota del proveedor y el endpoint de clasificación es público.
 * El estado vive en memoria del proceso. Fuera de un request HTTP no limita.
 */
@Component
@RequiredArgsConstructor
public class AiRateLimiter {
    private static final int PURGE_EVERY_CALLS = 500;

    private final AiProperties properties;
    private final ClientAddressResolver clientAddressResolver;
    private final Clock clock;

    private final Map<String, List<Instant>> calls = new ConcurrentHashMap<>();
    private int callsSincePurge;

    /**
     * Registra una consulta del cliente actual para la función indicada.
     *
     * @throws TooManyAiRequestsException si ya agotó su cupo dentro de la ventana
     */
    public void assertAllowed(String feature) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        Instant now = clock.instant();
        Duration window = properties.getRateLimitWindow();
        String key = feature + ":" + clientAddressResolver.resolve(attributes.getRequest());
        TooManyAiRequestsException[] blocked = new TooManyAiRequestsException[1];
        calls.compute(key, (ignored, previous) -> {
            List<Instant> recent = new ArrayList<>();
            if (previous != null) {
                previous.stream().filter(at -> at.isAfter(now.minus(window))).forEach(recent::add);
            }
            if (recent.size() >= properties.getMaxRequestsPerClient()) {
                blocked[0] = new TooManyAiRequestsException(
                        Duration.between(now, recent.get(0).plus(window)));
                return recent;
            }
            recent.add(now);
            return recent;
        });
        if (blocked[0] != null) {
            throw blocked[0];
        }
        purgeIfNeeded(now, window);
    }

    private synchronized void purgeIfNeeded(Instant now, Duration window) {
        if (++callsSincePurge < PURGE_EVERY_CALLS) {
            return;
        }
        callsSincePurge = 0;
        calls.entrySet().removeIf(entry -> entry.getValue().stream().noneMatch(at -> at.isAfter(now.minus(window))));
    }
}
