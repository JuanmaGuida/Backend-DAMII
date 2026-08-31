package com.reclamos.backend.controller;

import com.reclamos.backend.dto.auth.IdentityResponse;
import com.reclamos.backend.dto.auth.LoginRequest;
import com.reclamos.backend.dto.auth.LoginResponse;
import com.reclamos.backend.dto.error.ApiErrorResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.IdentityProvider;
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

    private final IdentityProvider identityProvider;

    public AuthController(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest request) {
        if (request == null || !request.hasRequiredFields()) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST);
        }

        return identityProvider.authenticate(request.username(), request.password())
                .<ResponseEntity<?>>map(session -> ResponseEntity.ok(LoginResponse.from(session)))
                .orElseGet(() -> ResponseEntity.status(401).body(INVALID_CREDENTIALS));
    }

    @GetMapping("/me")
    public IdentityResponse me(@AuthenticationPrincipal AuthenticatedIdentity identity) {
        return IdentityResponse.from(identity);
    }
}
