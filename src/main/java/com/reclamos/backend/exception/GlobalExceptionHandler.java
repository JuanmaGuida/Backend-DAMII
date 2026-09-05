package com.reclamos.backend.exception;

import com.reclamos.backend.dto.error.ApiErrorResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(FormValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({InvalidTicketRequestException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("El request es inválido")
                : exception.getMessage();
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "El cuerpo de la solicitud es inválido");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "El parámetro '" + exception.getName() + "' tiene un valor inválido");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Método HTTP no permitido");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Tipo de contenido no soportado");
    }

    @ExceptionHandler(EvidenceRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleEvidenceRequired(EvidenceRequiredException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, EvidenceRequiredException.CODE, exception.getMessage());
    }

    @ExceptionHandler(InformationRequestConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(InformationRequestConflictException exception) {
        return response(HttpStatus.CONFLICT, "INFORMATION_REQUEST_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(InformationRequestExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleExpired(InformationRequestExpiredException exception) {
        return response(HttpStatus.GONE, "INFORMATION_REQUEST_EXPIRED", exception.getMessage());
    }

    @ExceptionHandler(UnauthorizedTicketOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(UnauthorizedTicketOperationException exception) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }
}
