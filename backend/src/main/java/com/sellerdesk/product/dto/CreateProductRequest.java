package com.sellerdesk.product.dto;

import com.sellerdesk.product.service.ProductRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /products}. There is no {@code shopId} field: the shop comes from the JWT, and an
 * unknown {@code "shopId"} in the JSON is simply ignored.
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @Size(max = 50) String category,
        @NotNull
        @Size(min = 1, max = ProductRules.MAX_VARIANTS, message = "must have between 1 and 100 variants")
        List<@Valid @NotNull VariantRequest> variants) {
}
