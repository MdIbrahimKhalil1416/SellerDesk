package com.sellerdesk.product.dto;

import com.sellerdesk.product.service.ProductRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * {@code PATCH /products/{id}/variants/{variantId}}. Every field is optional: {@code null} means
 * "keep the current value". There is no {@code sku} (immutable) and no {@code stock} (Inventory).
 *
 * @param name   blank = go back to the default name from the attributes
 * @param active {@code false} deactivates the variant (variants are never deleted)
 */
public record UpdateVariantRequest(
        @Size(max = ProductRules.MAX_VARIANT_NAME) String name,

        @Size(max = ProductRules.MAX_ATTRIBUTES, message = "must have at most 5 attributes")
        Map<@NotBlank @Size(max = 30) @Pattern(regexp = ProductRules.ATTRIBUTE_KEY_REGEX,
                message = "must not contain '.' or '$'") String,
                @NotBlank @Size(max = 50) String> attributes,

        @Min(0) @Max(ProductRules.MAX_MONEY) Long price,

        @Min(0) @Max(ProductRules.MAX_MONEY) Long costPrice,

        @Min(0) @Max(ProductRules.MAX_LOW_STOCK_THRESHOLD) Integer lowStockThreshold,

        Boolean active,

        @NotNull Long version) {
}
