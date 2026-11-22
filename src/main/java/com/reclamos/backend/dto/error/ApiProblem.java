package com.reclamos.backend.dto.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

public final class ApiProblem {
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private ApiProblem() {
    }

    public static ResponseEntity.HeadersBuilder<?> response(HttpStatusCode status, String code, String detail) {
        return ResponseEntity.of(create(status, code, detail))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatusCode status,
                             String code, String detail, String requestPath) throws IOException {
        ProblemDetail problem = create(status, code, detail);
        if (!status.is5xxServerError() && requestPath != null && requestPath.startsWith("/")
                && !requestPath.startsWith("//")) {
            try {
                URI instance = URI.create(requestPath);
                if (instance.getRawQuery() == null && instance.getRawFragment() == null) {
                    problem.setInstance(instance);
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed request path must not prevent the error response.
            }
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }

    private static ProblemDetail create(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ABOUT_BLANK);
        if (HttpStatus.resolve(status.value()) == null) {
            problem.setTitle("HTTP Error");
        }
        problem.setProperty("code", code);
        problem.setProperty("message", detail);
        return problem;
    }
}
