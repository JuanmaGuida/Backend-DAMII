package com.reclamos.backend.exception;

import com.reclamos.backend.ai.TooManyAiRequestsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static com.reclamos.backend.dto.error.ApiProblem.response;

/**
 * La validación de {@code sort} en el listado de tickets no vive acá: se
 * hace explícita en {@code TicketService.listTickets} contra una whitelist
 * propia de propiedades ordenables, para no depender de excepciones
 * internas de Spring Data.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ProblemDetail> handlePreconditionFailed(PreconditionFailedException exception) {
        return response(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", exception.getMessage()).build();
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return response(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(OwnerTrackingCodeNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOwnerTrackingNotFound(OwnerTrackingCodeNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(TrackingCodeNotRecoverableException.class)
    public ResponseEntity<ProblemDetail> handleTrackingNotRecoverable(TrackingCodeNotRecoverableException exception) {
        return response(HttpStatus.CONFLICT, TrackingCodeNotRecoverableException.CODE, exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(TrackingCodeUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleTrackingUnavailable(TrackingCodeUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, TrackingCodeUnavailableException.CODE, exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentLifecycle(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "TICKET_STATE_CONFLICT",
                "El conjunto de tickets está siendo modificado; vuelva a consultar y reintente").build();
    }
    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(FormValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage()).build();
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage()).build();
    }

    @ExceptionHandler(StoredFileNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleStoredFileNotFound(StoredFileNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, StoredFileNotFoundException.CODE, exception.getMessage()).build();
    }

    @ExceptionHandler(TrackingTicketNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTrackingNotFound(TrackingTicketNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, TrackingTicketNotFoundException.CODE, exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(TooManyTrackingAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyTrackingAttempts(
            TooManyTrackingAttemptsException exception) {
        return response(HttpStatus.TOO_MANY_REQUESTS, TooManyTrackingAttemptsException.CODE, exception.getMessage())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException exception) {
        return response(HttpStatus.TOO_MANY_REQUESTS, TooManyLoginAttemptsException.CODE, exception.getMessage())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(TooManyAiRequestsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyAiRequests(
            TooManyAiRequestsException exception) {
        return response(HttpStatus.TOO_MANY_REQUESTS, TooManyAiRequestsException.CODE, exception.getMessage())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(InvalidAnonymousTicketCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAnonymousCredentials(
            InvalidAnonymousTicketCredentialsException exception) {
        return response(HttpStatus.UNAUTHORIZED, InvalidAnonymousTicketCredentialsException.CODE, exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler({InvalidTicketRequestException.class, InvalidCatalogRequestException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("El request es inválido")
                : exception.getMessage();
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "El cuerpo de la solicitud es inválido").build();
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(MissingServletRequestPartException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Falta el part obligatorio '" + exception.getRequestPartName() + "'").build();
    }

    @ExceptionHandler(TicketResolutionConflictException.class)
    public ResponseEntity<ProblemDetail> handleResolutionConflict(TicketResolutionConflictException exception) {
        return response(HttpStatus.CONFLICT, TicketResolutionConflictException.CODE, exception.getMessage()).build();
    }

    @ExceptionHandler(SatisfactionSurveyConflictException.class)
    public ResponseEntity<ProblemDetail> handleSatisfactionSurveyConflict(
            SatisfactionSurveyConflictException exception) {
        return response(HttpStatus.CONFLICT, SatisfactionSurveyConflictException.CODE, exception.getMessage()).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "El parámetro '" + exception.getName() + "' tiene un valor inválido").build();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método HTTP no permitido")
                .headers(exception.getHeaders()).build();
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, UnsupportedAttachmentMediaTypeException.class})
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(Exception exception) {
        var problem = response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Tipo de contenido no soportado");
        if (exception instanceof HttpMediaTypeNotSupportedException unsupported) {
            problem.headers(unsupported.getHeaders());
        }
        return problem.build();
    }

    @ExceptionHandler(InvalidAttachmentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAttachment(InvalidAttachmentException exception) {
        return response(HttpStatus.BAD_REQUEST, InvalidAttachmentException.CODE, exception.getMessage()).build();
    }

    @ExceptionHandler({AttachmentPayloadTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ProblemDetail> handlePayloadTooLarge(Exception exception) {
        String message = exception instanceof AttachmentPayloadTooLargeException
                ? exception.getMessage()
                : "La evidencia supera el tamaño máximo permitido.";
        return response(HttpStatus.PAYLOAD_TOO_LARGE, AttachmentPayloadTooLargeException.CODE, message).build();
    }

    @ExceptionHandler(AttachmentStorageUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAttachmentStorageUnavailable(
            AttachmentStorageUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, AttachmentStorageUnavailableException.CODE,
                exception.getMessage()).build();
    }

    @ExceptionHandler(EvidenceRequiredException.class)
    public ResponseEntity<ProblemDetail> handleEvidenceRequired(EvidenceRequiredException exception) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, EvidenceRequiredException.CODE, exception.getMessage()).build();
    }

    @ExceptionHandler(TicketStateConflictException.class)
    public ResponseEntity<ProblemDetail> handleTicketStateConflict(TicketStateConflictException exception) {
        return response(HttpStatus.CONFLICT, "TICKET_STATE_CONFLICT", exception.getMessage()).build();
    }

    @ExceptionHandler(InformationRequestConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(InformationRequestConflictException exception) {
        return response(HttpStatus.CONFLICT, "INFORMATION_REQUEST_CONFLICT", exception.getMessage()).build();
    }

    @ExceptionHandler(InformationRequestExpiredException.class)
    public ResponseEntity<ProblemDetail> handleExpired(InformationRequestExpiredException exception) {
        return response(HttpStatus.GONE, "INFORMATION_REQUEST_EXPIRED", exception.getMessage()).build();
    }

    @ExceptionHandler(UnauthorizedTicketOperationException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(UnauthorizedTicketOperationException exception) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage()).build();
    }

    @ExceptionHandler(InvalidAuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidAuthentication(InvalidAuthenticationException exception) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", exception.getMessage())
                .cacheControl(CacheControl.noStore()).build();
    }

    /**
     * QA (FAIL de "validaciones de datos y relaciones jerárquicas del
     * catálogo"): altas concurrentes con el mismo nombre/código terminaban
     * en 500 porque nada mapeaba las violaciones de constraints de base
     * (unique/check) que sí existen en el schema — {@code
     * CatalogAdminService} sólo hace un pre-check existsBy... antes de
     * guardar, que es TOCTOU-racy bajo concurrencia real. La constraint de
     * base sigue siendo la última línea de defensa (correcta: es atómica,
     * el pre-check no); acá se traduce a un 409 controlado en vez de dejar
     * que la excepción cruda de Hibernate/JDBC llegue como 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        return response(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT",
                "La operación entra en conflicto con datos existentes").build();
    }

    @ExceptionHandler(LabelConflictException.class)
    public ResponseEntity<ProblemDetail> handleLabelConflict(LabelConflictException exception) {
        return response(HttpStatus.CONFLICT, "LABEL_CONFLICT", exception.getMessage()).build();
    }
}
