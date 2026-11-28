package com.sellerdesk.courier.provider;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Webhook signature missing, wrong, or too old → {@code 401 INVALID_SIGNATURE}. The response is the
 * same for every cause (the cause is only in the server log), so an attacker learns nothing.
 */
public class InvalidSignatureException extends AppException {

    public static final String CODE = "INVALID_SIGNATURE";

    /** Only for the log line; never sent to the client. */
    private final String logReason;

    public InvalidSignatureException(String logReason) {
        super(HttpStatus.UNAUTHORIZED, CODE, "Invalid webhook signature");
        this.logReason = logReason;
    }

    public String logReason() {
        return logReason;
    }
}
