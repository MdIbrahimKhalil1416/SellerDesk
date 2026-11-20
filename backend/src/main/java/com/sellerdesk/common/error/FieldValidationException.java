package com.sellerdesk.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A validation rule that annotations cannot express (it needs two fields, or a service), but
 * should look to the client exactly like a Bean Validation error: {@code 400 VALIDATION_FAILED}
 * with {@code fieldErrors}. Example: "password must not be the same as the email".
 */
public class FieldValidationException extends AppException {

    public FieldValidationException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED, "Request validation failed",
                List.of(new ApiError.FieldError(field, message)));
    }
}
