package com.reclamos.backend.controller;

import com.reclamos.backend.dto.auth.IdentityResponse;
import com.reclamos.backend.dto.auth.LoginRequest;
import com.reclamos.backend.dto.auth.LoginResponse;
import com.reclamos.backend.dto.error.ApiErrorResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.security.LoginAttemptLimiter;
import com.reclamos.backend.security.M1SessionCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final ApiErrorResponse INVALID_REQUEST = new ApiErrorResponse(
            "INVALID_REQUEST",
            "Username y password son obligatorios"
    );
    private static final ApiErrorResponse INVALID_CREDENTIALS = new ApiErrorResponse(
            "INVALID_CREDENTIALS",
            "Usuario o contraseña inválidos"
    );

    private final AuthService authService;
    private final ObjectProvider<M1SessionCookies> cookies;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public AuthController(AuthService authService, ObjectProvider<M1SessionCookies> cookies,
                          LoginAttemptLimiter loginAttemptLimiter) {
        this.authService = authService;
        this.cookies = cookies;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest request,
                                   HttpServletRequest httpRequest, HttpServletResponse response) {
        if (request == null || !request.hasRequiredFields()) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST);
        }

        // DDA2-259: la reserva del intento es atómica y ocurre antes de consultar al proveedor de identidad.
        // Un origen o usuario bloqueado recibe 429 + Retry-After sin que se evalúen (ni se confirmen) sus
        // credenciales; las reservas concurrentes no pueden superar el máximo.
        LoginAttemptLimiter.Attempt attempt = loginAttemptLimiter.acquire(httpRequest, request.username());
        try {
            return authService.authenticate(request.username(), request.password())
                    .<ResponseEntity<?>>map(session -> {
                        loginAttemptLimiter.succeeded(attempt);
                        var sessionCookies = cookies.getIfAvailable();
                        if (sessionCookies != null) sessionCookies.write(response, session.refreshToken());
                        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginResponse.from(session));
                    })
                    .orElseGet(() -> {
                        // La reserva ya cuenta como fallo: credenciales inválidas.
                        var sessionCookies = cookies.getIfAvailable();
                        if (sessionCookies != null) sessionCookies.clear(response);
                        return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(INVALID_CREDENTIALS);
                    });
        } catch (RuntimeException notAnInvalidCredential) {
            // Proveedor caído, cuenta restringida, etc.: no es un intento fallido del cliente.
            loginAttemptLimiter.release(attempt);
            throw notAnInvalidCredential;
        }
    }

    @GetMapping("/me")
    public IdentityResponse me(@AuthenticationPrincipal AuthenticatedIdentity identity) {
        return IdentityResponse.from(identity);
    }
}
