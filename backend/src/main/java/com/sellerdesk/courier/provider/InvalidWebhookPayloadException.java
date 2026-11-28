package com.sellerdesk.courier.provider;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/** Correctly signed, but the body is not a payload we can read → {@code 400 MALFORMED_REQUEST}. */
public class InvalidWebhookPayloadException extends AppException {

    public InvalidWebhookPayloadException(String message) {
        super(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", message);
    }
}
