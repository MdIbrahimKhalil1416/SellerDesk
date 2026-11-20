package com.sellerdesk.product.service;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Delete refused: the product appears in orders or stock movements, or still has stock
 * → {@code 409 PRODUCT_IN_USE}. The frontend then suggests archiving instead.
 */
public class ProductInUseException extends AppException {

    public ProductInUseException() {
        super(HttpStatus.CONFLICT, ProductErrors.PRODUCT_IN_USE,
                "This product is used by orders or has stock, so it cannot be deleted. Archive it instead");
    }
}
