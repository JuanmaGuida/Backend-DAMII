package com.reclamos.backend.controller;

import com.reclamos.backend.dto.auth.LoginResponse;
import com.reclamos.backend.dto.error.ApiErrorResponse;
import com.reclamos.backend.security.LoginAttemptLimiter;
import com.reclamos.backend.security.M1SessionCookies;
import com.reclamos.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1SessionController {
    private final AuthService auth;
    private final M1SessionCookies cookies;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public M1SessionController(AuthService auth, M1SessionCookies cookies, LoginAttemptLimiter loginAttemptLimiter) {
        this.auth = auth;
        this.cookies = cookies;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
        var matching = request.getCookies() == null ? java.util.List.<jakarta.servlet.http.Cookie>of()
                : Arrays.stream(request.getCookies()).filter(cookie -> M1SessionCookies.NAME.equals(cookie.getName())).toList();
        String token = matching.size() == 1 ? matching.getFirst().getValue() : null;
        // DDA2-259: refresh sólo se limita por cliente (no hay usuario en el request). Sin cookie no hay nada
        // que adivinar (el SPA intenta un refresh silencioso al cargar): sólo se verifica, no se reserva.
        // Un refresh token que se presenta y es rechazado cuenta como fallo.
        LoginAttemptLimiter.Attempt attempt = null;
        if (token == null) {
            loginAttemptLimiter.assertAllowed(request, null);
        } else {
            attempt = loginAttemptLimiter.acquire(request, null);
        }
        final LoginAttemptLimiter.Attempt reserved = attempt;
        try {
            return auth.refresh(token).<ResponseEntity<?>>map(session -> {
                loginAttemptLimiter.succeeded(reserved);
                cookies.write(response, session.refreshToken());
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginResponse.from(session));
            }).orElseGet(() -> {
                cookies.clear(response);
                return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                        .body(new ApiErrorResponse("INVALID_TOKEN", "La sesión no es válida"));
            });
        } catch (RuntimeException notARejectedToken) {
            // M1 no disponible o respuesta inválida: no es un intento fallido del cliente.
            loginAttemptLimiter.release(reserved);
            throw notARejectedToken;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        // M1 does not promise immediate revocation. Only remove this browser's cookie.
        cookies.clear(response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
