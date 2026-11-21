package com.reclamos.backend.security;

import com.reclamos.backend.config.LoginRateLimitProperties;
import com.reclamos.backend.exception.TooManyLoginAttemptsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DDA2-259 (SEG-06): ventana deslizante por cliente y por usuario, sin guardar el usuario en claro. */
class LoginAttemptLimiterTest {
    private static final Instant START = Instant.parse("2026-11-16T12:00:00Z");
    private static final int CLIENT_MAX = 3;
    private static final int USERNAME_MAX = 2;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setMaxFailuresPerClient(CLIENT_MAX);
        properties.setMaxFailuresPerUsername(USERNAME_MAX);
        properties.setWindow(WINDOW);
        limiter = new LoginAttemptLimiter(properties, new ClientAddressResolver(List.of()), clock);
    }

    @Test
    void clientIsBlockedAfterTheMaximumAndRetryAfterMatchesTheWindow() {
        MockHttpServletRequest attacker = from("203.0.113.10");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.acquire(attacker, "user-" + attempt + "@example.test");
        }

        TooManyLoginAttemptsException blocked = assertThrows(TooManyLoginAttemptsException.class,
                () -> limiter.acquire(attacker, "another@example.test"));

        assertEquals(WINDOW.toSeconds(), blocked.getRetryAfterSeconds());
        assertDoesNotThrow(() -> limiter.acquire(from("203.0.113.11"), "fresh@example.test"));
    }

    @Test
    void blockedClientIsAlsoBlockedWithoutUsernameSoRefreshIsCovered() {
        MockHttpServletRequest attacker = from("203.0.113.12");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.acquire(attacker, null);
        }

        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.assertAllowed(attacker, null));
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.acquire(attacker, null));
    }

    @Test
    void usernameIsBlockedAcrossClientsAndNormalized() {
        limiter.acquire(from("203.0.113.20"), "Victim@Example.test");
        limiter.acquire(from("203.0.113.21"), "  victim@example.test ");

        MockHttpServletRequest freshClient = from("203.0.113.22");
        assertThrows(TooManyLoginAttemptsException.class,
                () -> limiter.acquire(freshClient, "VICTIM@example.test"));
        assertDoesNotThrow(() -> limiter.acquire(freshClient, "someone.else@example.test"));
    }

    @Test
    void aBlockedAttemptReservesNothing() {
        MockHttpServletRequest attacker = from("203.0.113.25");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.acquire(attacker, null);
        }
        // Los rechazados no suman: si sumaran, el bloqueo se prolongaría indefinidamente.
        for (int rejected = 0; rejected < 20; rejected++) {
            assertThrows(TooManyLoginAttemptsException.class, () -> limiter.acquire(attacker, null));
        }

        now.set(START.plus(WINDOW));
        assertDoesNotThrow(() -> limiter.acquire(attacker, null));
    }

    @Test
    void blockLiftsWhenTheOldestAttemptLeavesTheWindow() {
        MockHttpServletRequest attacker = from("203.0.113.30");
        for (int attempt = 0; attempt < CLIENT_MAX; attempt++) {
            limiter.acquire(attacker, null);
        }

        now.set(START.plus(WINDOW).minusSeconds(1));
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.assertAllowed(attacker, null));

        now.set(START.plus(WINDOW));
        assertDoesNotThrow(() -> limiter.assertAllowed(attacker, null));
        assertDoesNotThrow(() -> limiter.acquire(attacker, null));
    }

    @Test
    void successClearsTheUsernameButNotTheClientBudget() {
        MockHttpServletRequest owner = from("203.0.113.40");
        limiter.acquire(owner, "me@example.test");
        LoginAttemptLimiter.Attempt correct = limiter.acquire(owner, "me@example.test");

        limiter.succeeded(correct);

        // El cupo del usuario se restableció: otro cliente puede intentar con esa cuenta.
        assertDoesNotThrow(() -> limiter.assertAllowed(from("203.0.113.41"), "me@example.test"));
        // El del cliente conserva el intento anterior (1 de 3): quien tenga una cuenta propia no puede
        // resetear su cupo y seguir probando contraseñas ajenas. Quedan dos intentos, no tres.
        limiter.acquire(owner, "other-1@example.test");
        limiter.acquire(owner, "other-2@example.test");
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.acquire(owner, "other-3@example.test"));
    }

    @Test
    void releasingAnAttemptThatNeverEvaluatedCredentialsGivesTheBudgetBack() {
        MockHttpServletRequest client = from("203.0.113.45");
        // Proveedor de identidad caído: ninguno de estos intentos es una credencial inválida.
        for (int outage = 0; outage < CLIENT_MAX * 3; outage++) {
            limiter.release(limiter.acquire(client, "me@example.test"));
        }

        assertDoesNotThrow(() -> limiter.acquire(client, "me@example.test"));
        assertDoesNotThrow(() -> limiter.acquire(client, "me@example.test"));
    }

    @Test
    void settlingTwiceOrSettlingNullIsHarmless() {
        MockHttpServletRequest client = from("203.0.113.46");
        LoginAttemptLimiter.Attempt first = limiter.acquire(client, null);
        LoginAttemptLimiter.Attempt second = limiter.acquire(client, null);

        limiter.release(first);
        limiter.release(first);
        limiter.succeeded(first);
        limiter.release(null);
        limiter.succeeded(null);

        // Sólo se devolvió una reserva: quedan "second" y dos lugares libres, no tres.
        limiter.acquire(client, null);
        limiter.acquire(client, null);
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.acquire(client, null));
        assertThat(second).isNotNull();
    }

    @Test
    void theUsernameIsNeverKeptInClearText() {
        limiter.acquire(from("203.0.113.50"), "private.person@example.test");

        Map<?, ?> state = (Map<?, ?>) ReflectionTestUtils.getField(limiter, "failures");
        assertThat(state).isNotEmpty();
        assertThat(state.keySet().stream().map(String::valueOf))
                .noneMatch(key -> key.contains("private") || key.contains("@"));
    }

    /**
     * Antes la comprobación y el registro eran dos pasos: con N requests simultáneos todos pasaban el
     * chequeo y superaban el máximo. Ahora la reserva es atómica, así que pasan exactamente "máximo".
     */
    @Test
    void concurrentAttemptsFromOneClientNeverExceedTheMaximum() throws Exception {
        MockHttpServletRequest attacker = from("203.0.113.60");

        int[] outcome = race(60, index -> { limiter.acquire(attacker, "user-" + index + "@example.test"); });

        assertEquals(CLIENT_MAX, outcome[0], "pasaron más intentos que el máximo por cliente");
        assertEquals(60 - CLIENT_MAX, outcome[1]);
    }

    @Test
    void concurrentAttemptsAgainstOneAccountNeverExceedTheMaximum() throws Exception {
        int[] outcome = race(60, index -> { limiter.acquire(from("198.51.100." + (index + 1)), "victim@example.test"); });

        assertEquals(USERNAME_MAX, outcome[0], "pasaron más intentos que el máximo por usuario");
        assertEquals(60 - USERNAME_MAX, outcome[1]);
    }

    /** Lanza {@code threads} intentos a la vez; devuelve {permitidos, bloqueados}. */
    private static int[] race(int threads, java.util.function.IntConsumer attempt) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger blocked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < threads; index++) {
                final int current = index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        attempt.accept(current);
                        allowed.incrementAndGet();
                    } catch (TooManyLoginAttemptsException tooMany) {
                        blocked.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return new int[]{allowed.get(), blocked.get()};
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }
}
