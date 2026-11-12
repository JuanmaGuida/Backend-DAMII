package com.reclamos.backend.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConditionalOnExpression("${app.core.outbox.enabled:false} or ${app.core.inbox.enabled:false}")
public final class CoreOutboxSettings {
    private final String host;
    private final int port;
    private final String virtualHost;
    private final String username;
    private final String password;
    private final Set<UUID> approvedEventIds;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration confirmTimeout;
    private final Duration connectTimeout;

    public CoreOutboxSettings(@Value("${app.core.outbox.host}") String host,
                             @Value("${app.core.outbox.port}") int port,
                             @Value("${app.core.outbox.virtual-host}") String virtualHost,
                             @Value("${app.core.outbox.username}") String username,
                             @Value("${app.core.outbox.password}") String password,
                             @Value("${app.core.outbox.approved-event-ids}") String approved,
                             @Value("${app.core.outbox.max-attempts}") int maxAttempts,
                             @Value("${app.core.outbox.retry-delay}") Duration retryDelay,
                             @Value("${app.core.outbox.confirm-timeout}") Duration confirmTimeout,
                             @Value("${app.core.outbox.connect-timeout}") Duration connectTimeout) {
        if (host == null || !host.matches("[A-Za-z0-9.-]+") || port < 1 || port > 65535
                || virtualHost == null || virtualHost.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()) throw invalid();
        try {
            approvedEventIds = approved == null || approved.isBlank() ? Set.of() : Arrays.stream(approved.split(",", -1))
                    .map(String::trim).map(value -> {
                        UUID uuid = UUID.fromString(value);
                        if (!uuid.toString().equalsIgnoreCase(value) || uuid.version() != 4) throw invalid();
                        return uuid;
                    }).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException failure) { throw invalid(); }
        if (approvedEventIds.size() > 1000 || maxAttempts < 1 || maxAttempts > 20) throw invalid();
        bounded(retryDelay, Duration.ofSeconds(1), Duration.ofHours(1));
        bounded(confirmTimeout, Duration.ofMillis(100), Duration.ofSeconds(10));
        bounded(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(10));
        this.host = host; this.port = port; this.virtualHost = virtualHost;
        this.username = username; this.password = password; this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay; this.confirmTimeout = confirmTimeout; this.connectTimeout = connectTimeout;
    }

    private static void bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) throw invalid();
    }
    private static IllegalStateException invalid() {
        return new IllegalStateException("Core Outbox requires explicit credentials, valid event UUIDs and bounded retry settings");
    }
    public String host() { return host; }
    public int port() { return port; }
    public String virtualHost() { return virtualHost; }
    public String username() { return username; }
    public String password() { return password; }
    public Set<UUID> approvedEventIds() { return approvedEventIds; }
    public int maxAttempts() { return maxAttempts; }
    public Duration confirmTimeout() { return confirmTimeout; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration retryDelay(int attempt) {
        return retryDelay.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 10))
                .compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1)
                : retryDelay.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 10));
    }
}
