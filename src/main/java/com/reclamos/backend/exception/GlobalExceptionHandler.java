package com.reclamos.backend.exception;

import com.reclamos.backend.dto.response.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(FormValidationException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({InvalidTicketRequestException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("El request es inválido")
                : exception.getMessage();
        return response(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(EvidenceRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleEvidenceRequired(EvidenceRequiredException exception) {
        ApiErrorResponse body = new ApiErrorResponse(Instant.now(), 422,
                "Unprocessable Content", EvidenceRequiredException.CODE, exception.getMessage());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    @ExceptionHandler(InformationRequestConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(InformationRequestConflictException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(InformationRequestExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleExpired(InformationRequestExpiredException exception) {
        return response(HttpStatus.GONE, exception.getMessage());
    }

    @ExceptionHandler(UnauthorizedTicketOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(UnauthorizedTicketOperationException exception) {
        return response(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String message) {
        ApiErrorResponse body = new ApiErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), message);
        return ResponseEntity.status(status).body(body);
    }
}