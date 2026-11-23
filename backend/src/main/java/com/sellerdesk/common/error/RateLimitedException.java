package com.sellerdesk.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * {@code 429 Too Many Requests} that tells the client when to try again.
 * {@link GlobalExceptionHandler} adds the standard {@code Retry-After: <seconds>} header.
 */
@Getter
public class RateLimitedException extends AppException {

    private final long retryAfterSeconds;

    public RateLimitedException(String code, String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
