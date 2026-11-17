package com.sellerdesk.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for errors we throw on purpose. Each carries the HTTP status and a stable
 * {@code code}, so {@link GlobalExceptionHandler} can turn it into an {@link ApiError}
 * without knowing every subclass.
 *
 * <p>It is unchecked (extends RuntimeException) so services don't need {@code throws} everywhere,
 * and so Spring rolls back {@code @Transactional} methods when it is thrown.
 */
@Getter
public abstract class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
