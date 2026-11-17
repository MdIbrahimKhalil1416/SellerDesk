package com.sellerdesk.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Converts every exception thrown by controllers and services into one {@link ApiError} JSON format.
 *
 * <p>Spring picks the handler whose exception type is the closest match, so the
 * {@code Exception} handler at the bottom only runs when nothing more specific matched.
 *
 * <p>Security errors raised inside the filter chain (missing/invalid token) never reach this
 * class; they are written by {@code RestSecurityErrorHandler} in the same format.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String FORBIDDEN = "FORBIDDEN";
    static final String DUPLICATE_KEY = "DUPLICATE_KEY";
    static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // ---- Our own exceptions -------------------------------------------------------------

    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiError> handleAppException(AppException ex, HttpServletRequest request) {
        log.debug("{} {}: {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request, List.of());
    }

    // ---- Validation (400) ---------------------------------------------------------------

    /** {@code @Valid @RequestBody} failed. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                errors.add(new ApiError.FieldError(fe.getField(), fe.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.add(new ApiError.FieldError(ge.getObjectName(), ge.getDefaultMessage())));
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed", request, errors);
    }

    /** Constraints on {@code @RequestParam} / {@code @PathVariable} failed. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(err ->
                    errors.add(new ApiError.FieldError(name, err.getDefaultMessage())));
        });
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed", request, errors);
    }

    /** Constraints checked by {@code @Validated} beans (e.g. service method parameters). */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed", request, errors);
    }

    /** Body missing, not JSON, or a value of the wrong type (e.g. text for a number). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, MALFORMED_REQUEST,
                "Request body is missing or is not valid JSON", request, List.of());
    }

    // ---- Security (403) -----------------------------------------------------------------

    /** Thrown by {@code @PreAuthorize} when the logged-in user lacks a permission. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, FORBIDDEN,
                "You do not have permission to perform this action", request, List.of());
    }

    // ---- Database (409) -----------------------------------------------------------------

    /** A unique index rejected the write (e.g. same phone twice in one shop). */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
        log.debug("Duplicate key: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, DUPLICATE_KEY,
                "A record with the same unique value already exists", request, List.of());
    }

    /** Someone else saved the same document first ({@code @Version} mismatch). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION,
                "This record was changed by someone else. Reload and try again", request, List.of());
    }

    // ---- Everything else ----------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Spring MVC's own errors (404 unknown URL, 405 wrong method, 415 wrong content type...)
        // implement ErrorResponse and already know their status. Their messages are safe to show.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(status.value());
            String code = resolved != null ? resolved.name() : "ERROR";
            String message = errorResponse.getBody().getDetail() != null
                    ? errorResponse.getBody().getDetail()
                    : (resolved != null ? resolved.getReasonPhrase() : "Request failed");
            return build(status, code, message, request, List.of());
        }

        // A real bug or infrastructure failure: log everything, show the client nothing internal.
        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR,
                "Something went wrong. Please try again later", request.getRequestURI());
        log.error("Unexpected error [traceId={}] {} {}", body.traceId(), request.getMethod(),
                request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ApiError> build(HttpStatusCode status, String code, String message,
                                           HttpServletRequest request, List<ApiError.FieldError> fieldErrors) {
        ApiError body = ApiError.of(status, code, message, request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }
}
