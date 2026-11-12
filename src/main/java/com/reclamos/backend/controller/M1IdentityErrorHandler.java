package com.reclamos.backend.controller;

import com.reclamos.backend.dto.error.ApiErrorResponse;
import com.reclamos.backend.exception.IdentityAccessDeniedException;
import com.reclamos.backend.exception.IdentityProviderException;
import com.reclamos.backend.exception.InvalidSessionRequestException;
import com.reclamos.backend.security.M1SessionCookies;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AuthController.class, M1SessionController.class})
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1IdentityErrorHandler {
    private final M1SessionCookies cookies;

    public M1IdentityErrorHandler(M1SessionCookies cookies) { this.cookies = cookies; }

    @ExceptionHandler(InvalidSessionRequestException.class)
    public ResponseEntity<ApiErrorResponse> invalidRequest(InvalidSessionRequestException failure) {
        // A rejected cross-origin request must not log out the legitimate browser.
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("CSRF_REJECTED", failure.getMessage()));
    }

    @ExceptionHandler(IdentityAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> denied(IdentityAccessDeniedException failure, HttpServletResponse response) {
        cookies.clear(response);
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("FORBIDDEN", failure.getMessage()));
    }

    @ExceptionHandler(IdentityProviderException.class)
    public ResponseEntity<ApiErrorResponse> upstream(IdentityProviderException failure) {
        return ResponseEntity.status(failure.isUnavailable() ? 503 : 502).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(failure.isUnavailable() ? "IDENTITY_UNAVAILABLE" : "IDENTITY_RESPONSE_INVALID", failure.getMessage()));
    }
}
