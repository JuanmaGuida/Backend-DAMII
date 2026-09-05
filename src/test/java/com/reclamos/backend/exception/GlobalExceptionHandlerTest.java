package com.reclamos.backend.exception;

import com.reclamos.backend.dto.error.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void evidenceRequiredUsesSemanticCodeAndUnprocessableContent() {
        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler()
                .handleEvidenceRequired(new EvidenceRequiredException());

        assertEquals(422, response.getStatusCode().value());
        assertEquals("EVIDENCE_REQUIRED", response.getBody().code());
        assertEquals("El nivel de riesgo detectado requiere adjuntar al menos una evidencia.",
                response.getBody().message());
    }
}
