package com.reclamos.backend.controller;

import com.reclamos.backend.exception.IdentityAccessDeniedException;
import com.reclamos.backend.exception.IdentityProviderException;
import com.reclamos.backend.exception.InvalidSessionRequestException;
import com.reclamos.backend.security.M1SessionCookies;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.reclamos.backend.dto.error.ApiProblem.response;

@RestControllerAdvice(assignableTypes = {AuthController.class, M1SessionController.class})
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1IdentityErrorHandler {
    private final M1SessionCookies cookies;

    public M1IdentityErrorHandler(M1SessionCookies cookies) { this.cookies = cookies; }

    @ExceptionHandler(InvalidSessionRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(InvalidSessionRequestException failure) {
        // A rejected cross-origin request must not log out the legitimate browser.
        return response(HttpStatus.FORBIDDEN, "CSRF_REJECTED", failure.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(IdentityAccessDeniedException.class)
    public ResponseEntity<ProblemDetail> denied(IdentityAccessDeniedException failure, HttpServletResponse response) {
        cookies.clear(response);
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", failure.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(IdentityProviderException.class)
    public ResponseEntity<ProblemDetail> upstream(IdentityProviderException failure) {
        HttpStatus status = failure.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        String code = failure.isUnavailable() ? "IDENTITY_UNAVAILABLE" : "IDENTITY_RESPONSE_INVALID";
        return response(status, code, failure.getMessage()).cacheControl(CacheControl.noStore()).build();
    }
}
