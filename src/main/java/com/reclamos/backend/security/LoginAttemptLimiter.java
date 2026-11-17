package com.reclamos.backend.security;

import com.reclamos.backend.config.LoginRateLimitProperties;
import com.reclamos.backend.exception.TooManyLoginAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limita los intentos fallidos de {@code /api/auth/login} y {@code /api/auth/refresh}
 * (DDA2-259, SEG-06).
 *
 * <p>Los fallos se acumulan en dos ejes: la dirección del cliente (frena la
 * fuerza bruta desde un mismo origen) y el usuario (frena la adivinanza de la
 * contraseña de una cuenta aunque cambie el origen). Superado el umbral de
 * cualquiera de los dos se responde 429 <em>sin consultar al proveedor de
 * identidad</em>, de modo que la respuesta no revela si las credenciales eran
 * correctas y M1 no recibe el tráfico del ataque.</p>
 *
 * <p>El usuario se guarda como hash SHA-256 de su valor normalizado: el estado
 * en memoria nunca retiene el correo. Un login exitoso limpia sólo el contador
 * de ese usuario; el del cliente no se reinicia, para que quien tenga una cuenta
 * propia no pueda resetear su cupo y seguir probando contraseñas ajenas.</p>
 *
 * <p>El estado vive en memoria del proceso: se reinicia con el servicio y no se
 * comparte entre réplicas. La dirección del cliente se resuelve con
 * {@link ClientAddressResolver}, igual que el límite de tracking.</p>
 */
@Component
public class LoginAttemptLimiter {
    private static final String CLIENT_PREFIX = "client:";
    private static final String USERNAME_PREFIX = "username:";
    private static final int PURGE_EVERY_FAILURES = 500;

    private final LoginRateLimitProperties properties;
    private final ClientAddressResolver clientAddressResolver;
    private final Clock clock;

    /** Instantes de los últimos fallos por clave; las listas son inmutables. */
    private final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();
    private final AtomicInteger failuresSincePurge = new AtomicInteger();

    public LoginAttemptLimiter(LoginRateLimitProperties properties,
                               ClientAddressResolver clientAddressResolver, Clock clock) {
        this.properties = properties;
        this.clientAddressResolver = clientAddressResolver;
        this.clock = clock;
    }

    /**
     * Rechaza el intento si el cliente del request o el usuario indicado
     * alcanzaron su máximo de fallos.
     *
     * @param username usuario que intenta autenticarse; {@code null} o vacío
     *                 cuando sólo aplica el límite por cliente (refresh)
     */
    public void assertAllowed(HttpServletRequest request, String username) {
        Instant now = clock.instant();
        Duration clientWait = blockedFor(clientKey(request), properties.getMaxFailuresPerClient(), now);
        String usernameKey = usernameKey(username);
        Duration usernameWait = usernameKey == null
                ? Duration.ZERO
                : blockedFor(usernameKey, properties.getMaxFailuresPerUsername(), now);
        Duration wait = clientWait.compareTo(usernameWait) >= 0 ? clientWait : usernameWait;
        if (!wait.isZero()) {
            throw new TooManyLoginAttemptsException(wait);
        }
    }

    /** Registra un intento fallido contra el cliente del request y, si hay usuario, contra éste. */
    public void recordFailure(HttpServletRequest request, String username) {
        Instant now = clock.instant();
        record(clientKey(request), now);
        String usernameKey = usernameKey(username);
        if (usernameKey != null) {
            record(usernameKey, now);
        }
        if (failuresSincePurge.incrementAndGet() >= PURGE_EVERY_FAILURES) {
            failuresSincePurge.set(0);
            failures.entrySet().removeIf(entry -> recentFailures(entry.getValue(), now).isEmpty());
        }
    }

    /** Un login exitoso limpia los fallos previos de ese usuario (no los del cliente). */
    public void recordSuccess(String username) {
        String usernameKey = usernameKey(username);
        if (usernameKey != null) {
            failures.remove(usernameKey);
        }
    }

    private String clientKey(HttpServletRequest request) {
        return CLIENT_PREFIX + clientAddressResolver.resolve(request);
    }

    private static String usernameKey(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(username.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return USERNAME_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 es obligatorio en toda JVM; si faltara, fallar cerrado es lo correcto.
            throw new IllegalStateException(unavailable);
        }
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
        int retained = Math.max(properties.getMaxFailuresPerClient(), properties.getMaxFailuresPerUsername());
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
