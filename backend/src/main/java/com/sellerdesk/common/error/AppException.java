package com.sellerdesk.common.error;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for errors we throw on purpose. Each carries the HTTP status and a stable
 * {@code code}, so {@link GlobalExceptionHandler} can turn it into an {@link ApiError}
 * without knowing every subclass.
 *
 * <p>It is unchecked (extends RuntimeException) so services don't need {@code throws} everywhere,
 * and so Spring rolls back {@code @Transactional} methods when it is thrown.
 *
 * <p>Optional {@code fieldErrors} point the client at the fields that caused the error, also for
 * non-400 errors (e.g. {@code 409 SKU_ALREADY_EXISTS} on {@code variants[2].sku}).
 */
@Getter
public abstract class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fieldErrors;

    protected AppException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    protected AppException(HttpStatus status, String code, String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }
}
