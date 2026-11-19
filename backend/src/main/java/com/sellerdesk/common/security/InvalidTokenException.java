package com.sellerdesk.common.security;

import lombok.Getter;

/**
 * A JWT could not be accepted. {@link Reason#EXPIRED} is reported separately so the frontend
 * knows to call {@code /auth/refresh}; every other problem is {@link Reason#INVALID}.
 *
 * <p>The message is for logs only. Clients get a generic text from RestSecurityErrorHandler.
 */
@Getter
public class InvalidTokenException extends RuntimeException {

    public enum Reason { EXPIRED, INVALID }

    private final Reason reason;

    public InvalidTokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public InvalidTokenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
