package com.reclamos.backend.identity.m1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

@Component
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public final class M1Settings {
    private final URI baseUrl;
    private final String browserOrigin;
    private final SecretKey verificationKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public M1Settings(@Value("${app.identity.m1.base-url}") String baseUrl,
                      @Value("${app.identity.m1.jwt-secret-base64}") String encodedKey,
                      @Value("${app.identity.m1.browser-origin}") String browserOrigin,
                      @Value("${app.identity.m1.connect-timeout}") Duration connectTimeout,
                      @Value("${app.identity.m1.read-timeout}") Duration readTimeout) {
        this.baseUrl = httpsOrigin(baseUrl);
        this.browserOrigin = httpsOrigin(browserOrigin).toString();
        this.connectTimeout = positive(connectTimeout);
        this.readTimeout = positive(readTimeout);
        byte[] bytes = null;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length < 32) throw new IllegalArgumentException();
            this.verificationKey = new SecretKeySpec(bytes, "HmacSHA256");
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("M1 requires a Base64 APP_JWT_SECRET of at least 256 decoded bits");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    public static URI httpsOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            return new URI("https", null, uri.getHost().toLowerCase(java.util.Locale.ROOT), port, null, null, null);
        } catch (Exception invalid) {
            throw new IllegalStateException("M1 base URL and M2 browser origin must be explicit HTTPS origins");
        }
    }

    private static Duration positive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("M1 HTTP timeouts must be positive");
        }
        return duration;
    }

    public URI baseUrl() { return baseUrl; }
    public String browserOrigin() { return browserOrigin; }
    public SecretKey verificationKey() { return verificationKey; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
}
