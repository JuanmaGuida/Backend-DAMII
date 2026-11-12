package com.reclamos.backend.security;

import com.reclamos.backend.identity.m1.M1IdentityProvider;
import com.reclamos.backend.identity.m1.M1Settings;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1SessionCookies {
    public static final String NAME = "__Host-M2-Refresh";
    public static final String CSRF_HEADER = "X-M2-CSRF";
    private final String browserOrigin;

    public M1SessionCookies(M1Settings settings) { browserOrigin = settings.browserOrigin(); }

    public String browserOrigin() { return browserOrigin; }

    public void write(HttpServletResponse response, String token) {
        if (!M1IdentityProvider.validRefreshToken(token)) throw new IllegalArgumentException("Invalid refresh cookie");
        cookie(response, token, Duration.ofDays(7));
    }

    public void clear(HttpServletResponse response) { cookie(response, "", Duration.ZERO); }

    private void cookie(HttpServletResponse response, String value, Duration maxAge) {
        // __Host- requires Secure, Path=/ and no Domain, preventing sibling-domain cookie injection.
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, value)
                .httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(maxAge).build().toString());
    }
}
