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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limita los intentos fallidos de {@code /api/auth/login} y {@code /api/auth/refresh}
 * (DDA2-259, SEG-06).
 *
 * <p>Cada intento se reserva de forma atómica antes de evaluar credenciales y se acumula en dos ejes: la dirección del cliente (frena la
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
    private static final int PURGE_EVERY_ATTEMPTS = 500;

    private final LoginRateLimitProperties properties;
    private final ClientAddressResolver clientAddressResolver;
    private final Clock clock;

    /** Instantes de los últimos fallos por clave; las listas son inmutables. */
    private final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();
    private final AtomicInteger attemptsSincePurge = new AtomicInteger();
    /** Serializa las operaciones compuestas (leer + decidir + escribir) sobre {@link #failures}. */
    private final Object lock = new Object();

    public LoginAttemptLimiter(LoginRateLimitProperties properties,
                               ClientAddressResolver clientAddressResolver, Clock clock) {
        this.properties = properties;
        this.clientAddressResolver = clientAddressResolver;
        this.clock = clock;
    }

    /**
     * Comprueba y <em>reserva</em> el intento en una sola operación atómica: si el cliente o el usuario ya
     * alcanzaron su máximo lanza 429 sin reservar nada; si no, deja registrado el intento antes de que se
     * consulte al proveedor de identidad. Así, N requests concurrentes no pueden pasar todos el chequeo
     * y superar el límite (la comprobación y el registro ya no son dos pasos separados).
     *
     * <p>La reserva cuenta como fallo hasta que se la salda: {@link #succeeded(Attempt)} la devuelve al
     * cliente y limpia al usuario; {@link #release(Attempt)} la devuelve cuando el intento no llegó a
     * evaluar credenciales (el proveedor estaba caído, la cuenta está restringida, etc.).</p>
     *
     * @param username usuario que intenta autenticarse; {@code null} o vacío cuando sólo aplica el
     *                 límite por cliente (refresh)
     */
    public Attempt acquire(HttpServletRequest request, String username) {
        Instant now = clock.instant();
        String clientKey = clientKey(request);
        String usernameKey = usernameKey(username);
        synchronized (lock) {
            throwIfBlocked(clientKey, usernameKey, now);
            record(clientKey, now);
            if (usernameKey != null) {
                record(usernameKey, now);
            }
            if (attemptsSincePurge.incrementAndGet() >= PURGE_EVERY_ATTEMPTS) {
                attemptsSincePurge.set(0);
                failures.entrySet().removeIf(entry -> recentFailures(entry.getValue(), now).isEmpty());
            }
        }
        return new Attempt(clientKey, usernameKey, now);
    }

    /** Sólo verifica (no reserva): para requests que no pueden adivinar nada, como un refresh sin cookie. */
    public void assertAllowed(HttpServletRequest request, String username) {
        Instant now = clock.instant();
        synchronized (lock) {
            throwIfBlocked(clientKey(request), usernameKey(username), now);
        }
    }

    /**
     * Login correcto: se limpia el contador de ese usuario y se devuelve el intento al cliente, de modo
     * que un acierto no consume cupo. El del cliente no se reinicia: quien tenga una cuenta propia no
     * puede resetear su cupo y seguir probando contraseñas ajenas.
     */
    public void succeeded(Attempt attempt) {
        if (attempt == null || !attempt.settle()) {
            return;
        }
        synchronized (lock) {
            refund(attempt.clientKey, attempt.at);
            if (attempt.usernameKey != null) {
                failures.remove(attempt.usernameKey);
            }
        }
    }

    /** El intento no llegó a evaluar credenciales (error del proveedor, cuenta restringida): no cuenta. */
    public void release(Attempt attempt) {
        if (attempt == null || !attempt.settle()) {
            return;
        }
        synchronized (lock) {
            refund(attempt.clientKey, attempt.at);
            if (attempt.usernameKey != null) {
                refund(attempt.usernameKey, attempt.at);
            }
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

    private void throwIfBlocked(String clientKey, String usernameKey, Instant now) {
        Duration clientWait = blockedFor(clientKey, properties.getMaxFailuresPerClient(), now);
        Duration usernameWait = usernameKey == null
                ? Duration.ZERO
                : blockedFor(usernameKey, properties.getMaxFailuresPerUsername(), now);
        Duration wait = clientWait.compareTo(usernameWait) >= 0 ? clientWait : usernameWait;
        if (!wait.isZero()) {
            throw new TooManyLoginAttemptsException(wait);
        }
    }

    private void refund(String key, Instant at) {
        failures.computeIfPresent(key, (ignored, recorded) -> {
            List<Instant> updated = new ArrayList<>(recorded);
            updated.remove(at);
            return updated.isEmpty() ? null : List.copyOf(updated);
        });
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

    /** Intento reservado por {@link #acquire}; se salda una sola vez con {@code succeeded} o {@code release}. */
    public static final class Attempt {
        private final String clientKey;
        private final String usernameKey;
        private final Instant at;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Attempt(String clientKey, String usernameKey, Instant at) {
            this.clientKey = clientKey;
            this.usernameKey = usernameKey;
            this.at = at;
        }

        private boolean settle() {
            return settled.compareAndSet(false, true);
        }
    }
}
