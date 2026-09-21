package com.reclamos.backend.exception;

import com.reclamos.backend.dto.error.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void satisfactionSurveyConflictUsesSemanticCode() {
        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler()
                .handleSatisfactionSurveyConflict(new SatisfactionSurveyConflictException("Encuesta duplicada"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("SATISFACTION_SURVEY_CONFLICT", response.getBody().code());
        assertEquals("Encuesta duplicada", response.getBody().message());
    }
    // QA (FAIL de "validaciones de datos y relaciones jerárquicas del
    // catálogo"): antes de este handler, una DataIntegrityViolationException
    // (típicamente una constraint de base violada por una condición de
    // carrera que el pre-check existsBy... de CatalogAdminService no
    // alcanza a evitar) no tenía ningún @ExceptionHandler que la mapeara y
    // llegaba como 500 crudo.
    @Test
    void dataIntegrityViolationMapsToControlledConflict() {
        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler()
                .handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate key value"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("DATA_INTEGRITY_CONFLICT", response.getBody().code());
        assertEquals("La operación entra en conflicto con datos existentes", response.getBody().message());
    }

    @Test
    void evidenceRequiredUsesSemanticCodeAndUnprocessableContent() {
        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler()
                .handleEvidenceRequired(new EvidenceRequiredException());

        assertEquals(422, response.getStatusCode().value());
        assertEquals("EVIDENCE_REQUIRED", response.getBody().code());
        assertEquals("El nivel de riesgo detectado requiere adjuntar al menos una evidencia.",
                response.getBody().message());
    }

    @Test
    void attachmentErrorsUseCanonicalStatusesAndCodes() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiErrorResponse> invalid = handler.handleInvalidAttachment(
                new InvalidAttachmentException("Archivo inválido"));
        ResponseEntity<ApiErrorResponse> tooLarge = handler.handlePayloadTooLarge(
                new AttachmentPayloadTooLargeException("Demasiado grande"));
        ResponseEntity<ApiErrorResponse> springTooLarge = handler.handlePayloadTooLarge(
                new MaxUploadSizeExceededException(10));
        ResponseEntity<ApiErrorResponse> unsupported = handler.handleUnsupportedMediaType(
                new UnsupportedAttachmentMediaTypeException("No permitido"));
        ResponseEntity<ApiErrorResponse> unavailable = handler.handleAttachmentStorageUnavailable(
                new AttachmentStorageUnavailableException());

        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVALID_ATTACHMENT", invalid.getBody().code());
        assertEquals(413, tooLarge.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", tooLarge.getBody().code());
        assertEquals(413, springTooLarge.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE", springTooLarge.getBody().code());
        assertEquals(415, unsupported.getStatusCode().value());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", unsupported.getBody().code());
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("ATTACHMENT_STORAGE_UNAVAILABLE", unavailable.getBody().code());
    }
}
