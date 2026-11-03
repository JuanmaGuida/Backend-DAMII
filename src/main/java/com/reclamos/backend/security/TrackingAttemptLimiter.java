package com.reclamos.backend.security;

import com.reclamos.backend.config.TrackingRateLimitProperties;
import com.reclamos.backend.exception.TooManyTrackingAttemptsException;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limita los intentos fallidos de acceso anónimo por tracking (DDA2-197).
 *
 * <p>Los fallos se acumulan en dos ejes: la dirección del cliente (frena
 * barridos de códigos desde un mismo origen) y el ticket existente (frena la
 * adivinanza de la contraseña de un ticket aunque cambie el origen). Superado
 * el umbral de cualquiera de los dos se responde 429 sin evaluar las
 * credenciales, de modo que la respuesta no revela si eran correctas.</p>
 *
 * <p>El estado vive en memoria del proceso: se reinicia con el servicio y no
 * se comparte entre réplicas.</p>
 */
@Component
@RequiredArgsConstructor
public class TrackingAttemptLimiter {
    private static final String CLIENT_PREFIX = "client:";
    private static final String TICKET_PREFIX = "ticket:";
    private static final int PURGE_EVERY_FAILURES = 500;

    private final TrackingRateLimitProperties properties;
    private final ClientAddressResolver clientAddressResolver;
    private final Clock clock;

    /** Instantes de los últimos fallos por clave; las listas son inmutables. */
    private final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();
    private final AtomicInteger failuresSincePurge = new AtomicInteger();

    /**
     * Rechaza el intento si el cliente actual o el ticket indicado alcanzaron
     * su máximo de fallos.
     *
     * @param trackingCodeHash hash del código si corresponde a un ticket
     *                         existente; {@code null} cuando sólo aplica el
     *                         límite por cliente
     */
    public void assertAllowed(String trackingCodeHash) {
        Instant now = clock.instant();
        Duration clientWait = currentClientKey()
                .map(key -> blockedFor(key, properties.getMaxFailuresPerClient(), now))
                .orElse(Duration.ZERO);
        Duration ticketWait = trackingCodeHash == null
                ? Duration.ZERO
                : blockedFor(TICKET_PREFIX + trackingCodeHash, properties.getMaxFailuresPerTicket(), now);
        Duration wait = clientWait.compareTo(ticketWait) >= 0 ? clientWait : ticketWait;
        if (!wait.isZero()) {
            throw new TooManyTrackingAttemptsException(wait);
        }
    }

    /**
     * Registra un acceso fallido contra el cliente actual y, si el código
     * identificó un ticket existente, contra ese ticket.
     */
    public void recordFailure(String trackingCodeHash) {
        Instant now = clock.instant();
        currentClientKey().ifPresent(key -> record(key, now));
        if (trackingCodeHash != null) {
            record(TICKET_PREFIX + trackingCodeHash, now);
        }
        if (failuresSincePurge.incrementAndGet() >= PURGE_EVERY_FAILURES) {
            failuresSincePurge.set(0);
            failures.entrySet().removeIf(entry -> recentFailures(entry.getValue(), now).isEmpty());
        }
    }

    // Fuera de un request HTTP (jobs, tests de servicio) no hay cliente que limitar.
    private Optional<String> currentClientKey() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(CLIENT_PREFIX + clientAddressResolver.resolve(attributes.getRequest()));
        }
        return Optional.empty();
    }

    /** Tiempo restante de bloqueo para la clave, o cero si todavía puede intentar. */
    private Duration blockedFor(String key, int maxFailures, Instant now) {
        List<Instant> recent = recentFailures(failures.get(key), now);
        if (recent.size() < maxFailures) {
            return Duration.ZERO;
        }
        // Se libera cuando el fallo que completó el cupo sale de la ventana.
        Instant releasedAt = recent.get(recent.size() - maxFailures).plus(properties.getWindow());
        return Duration.between(now, releasedAt);
    }

    private void record(String key, Instant now) {
        int retained = Math.max(properties.getMaxFailuresPerClient(), properties.getMaxFailuresPerTicket());
        failures.compute(key, (ignored, recorded) -> {
            List<Instant> updated = new ArrayList<>(recentFailures(recorded, now));
            updated.add(now);
            return List.copyOf(updated.subList(Math.max(0, updated.size() - retained), updated.size()));
        });
    }

    private List<Instant> recentFailures(List<Instant> recorded, Instant now) {
        if (recorded == null) {
            return List.of();
        }
        Instant windowStart = now.minus(properties.getWindow());
        return recorded.stream().filter(at -> at.isAfter(windowStart)).toList();
    }
}
