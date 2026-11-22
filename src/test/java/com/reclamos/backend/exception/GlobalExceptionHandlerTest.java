package com.reclamos.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void satisfactionSurveyConflictUsesSemanticCode() {
        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler()
                .handleSatisfactionSurveyConflict(new SatisfactionSurveyConflictException("Encuesta duplicada"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals(409, response.getBody().getStatus());
        assertEquals("about:blank", response.getBody().getType().toString());
        assertEquals("Conflict", response.getBody().getTitle());
        assertEquals("Encuesta duplicada", response.getBody().getDetail());
        assertEquals("SATISFACTION_SURVEY_CONFLICT", response.getBody().getProperties().get("code"));
        assertEquals("Encuesta duplicada", response.getBody().getProperties().get("message"));
    }
    // QA (FAIL de "validaciones de datos y relaciones jerárquicas del
    // catálogo"): antes de este handler, una DataIntegrityViolationException
    // (típicamente una constraint de base violada por una condición de
    // carrera que el pre-check existsBy... de CatalogAdminService no
    // alcanza a evitar) no tenía ningún @ExceptionHandler que la mapeara y
    // llegaba como 500 crudo.
    @Test
    void dataIntegrityViolationMapsToControlledConflict() {
        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler()
                .handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate key value"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("DATA_INTEGRITY_CONFLICT", response.getBody().getProperties().get("code"));
        assertEquals("La operación entra en conflicto con datos existentes", response.getBody().getProperties().get("message"));
    }

    @Test
    void evidenceRequiredUsesSemanticCodeAndUnprocessableContent() {
        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler()
                .handleEvidenceRequired(new EvidenceRequiredException());

        assertEquals(422, response.getStatusCode().value());
        assertEquals("EVIDENCE_REQUIRED", response.getBody().getProperties().get("code"));
        assertEquals("El nivel de riesgo detectado requiere adjuntar al menos una evidencia.",
                response.getBody().getProperties().get("message"));
    }

    @Test
    void attachmentErrorsUseCanonicalStatusesAndCodes() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ProblemDetail> invalid = handler.handleInvalidAttachment(
                new InvalidAttachmentException("Archivo inválido"));
        ResponseEntity<ProblemDetail> tooLarge = handler.handlePayloadTooLarge(
                new AttachmentPayloadTooLargeException("Demasiado grande"));
        ResponseEntity<ProblemDetail> springTooLarge = handler.handlePayloadTooLarge(
                new MaxUploadSizeExceededException(10));
        ResponseEntity<ProblemDetail> unsupported = handler.handleUnsupportedMediaType(
                new UnsupportedAttachmentMediaTypeException("No permitido"));
        ResponseEntity<ProblemDetail> unavailable = handler.handleAttachmentStorageUnavailable(
                new AttachmentStorageUnavailableException());

        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_ATTACHMENT", invalid.getBody().getProperties().get("code"));
        assertEquals(413, tooLarge.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", tooLarge.getBody().getProperties().get("code"));
        assertEquals(413, springTooLarge.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", springTooLarge.getBody().getProperties().get("code"));
        assertEquals(415, unsupported.getStatusCode().value());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", unsupported.getBody().getProperties().get("code"));
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("ATTACHMENT_STORAGE_UNAVAILABLE", unavailable.getBody().getProperties().get("code"));
    }
}
