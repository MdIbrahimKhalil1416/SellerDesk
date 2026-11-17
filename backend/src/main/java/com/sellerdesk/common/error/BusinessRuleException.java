package com.sellerdesk.common.error;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request is well-formed but breaks a business rule, for example
 * {@code INSUFFICIENT_STOCK} or {@code INVALID_STATUS_TRANSITION}.
 *
 * <p>Defaults to {@code 409 Conflict}. Pass another status when it fits better,
 * e.g. {@code 400} for {@code INVALID_OTP} or {@code 429} for rate limits.
 */
public class BusinessRuleException extends AppException {

    public BusinessRuleException(String code, String message) {
        this(HttpStatus.CONFLICT, code, message);
    }

    public BusinessRuleException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
