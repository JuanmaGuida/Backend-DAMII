package com.reclamos.backend.controller;

import com.reclamos.backend.dto.auth.IdentityResponse;
import com.reclamos.backend.dto.auth.LoginRequest;
import com.reclamos.backend.dto.auth.LoginResponse;
import com.reclamos.backend.dto.error.ApiErrorResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.security.M1SessionCookies;
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

    public AuthController(AuthService authService, ObjectProvider<M1SessionCookies> cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest request, HttpServletResponse response) {
        if (request == null || !request.hasRequiredFields()) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST);
        }

        return authService.authenticate(request.username(), request.password())
                .<ResponseEntity<?>>map(session -> {
                    var sessionCookies = cookies.getIfAvailable();
                    if (sessionCookies != null) sessionCookies.write(response, session.refreshToken());
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginResponse.from(session));
                })
                .orElseGet(() -> {
                    var sessionCookies = cookies.getIfAvailable();
                    if (sessionCookies != null) sessionCookies.clear(response);
                    return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(INVALID_CREDENTIALS);
                });
    }

    @GetMapping("/me")
    public IdentityResponse me(@AuthenticationPrincipal AuthenticatedIdentity identity) {
        return IdentityResponse.from(identity);
    }
}
