package com.sellerdesk.common.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested resource does not exist <b>in the current shop</b>.
 * Also used when the id belongs to another shop: we never reveal that it exists (→ 404).
 */
public class ResourceNotFoundException extends AppException {

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }

    /**
     * Convenience factory: {@code of("Order")} → code {@code ORDER_NOT_FOUND},
     * message {@code "Order not found"}.
     */
    public static ResourceNotFoundException of(String resourceName) {
        String code = resourceName.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_") + "_NOT_FOUND";
        return new ResourceNotFoundException(code, resourceName + " not found");
    }
}
