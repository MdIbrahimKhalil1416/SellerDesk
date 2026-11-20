package com.sellerdesk.product.service;

import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.AppException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A SKU is already used by another product of this shop → {@code 409 SKU_ALREADY_EXISTS}, with
 * {@code fieldErrors} naming each clashing field (e.g. {@code variants[2].sku}) so the form can
 * highlight it. More specific than the generic {@code DUPLICATE_KEY}, which would not say which SKU.
 */
public class SkuAlreadyExistsException extends AppException {

    public SkuAlreadyExistsException(List<ApiError.FieldError> fieldErrors) {
        super(HttpStatus.CONFLICT, ProductErrors.SKU_ALREADY_EXISTS,
                "This SKU is already used by another variant of your shop", fieldErrors);
    }

    public static SkuAlreadyExistsException forField(String field, String sku) {
        return new SkuAlreadyExistsException(List.of(new ApiError.FieldError(field, "SKU " + sku + " already exists")));
    }
}
