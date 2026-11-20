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
 * A new variant, inside {@link CreateProductRequest}. No {@code stock}: opening stock is entered
 * through Inventory. Money in poisha (৳1 = 100).
 *
 * @param sku        trimmed and uppercased by the service; letters, digits, {@code . _ -}, max 40
 * @param name       optional; default = attribute values joined by {@code " / "}
 * @param attributes e.g. {@code {"Color": "Red", "Size": "M"}}, max 5 pairs
 */
public record VariantRequest(
        @NotBlank
        @Pattern(regexp = ProductRules.SKU_INPUT_REGEX, message = ProductRules.SKU_MESSAGE)
        String sku,

        @Size(max = ProductRules.MAX_VARIANT_NAME) String name,

        @Size(max = ProductRules.MAX_ATTRIBUTES, message = "must have at most 5 attributes")
        Map<@NotBlank @Size(max = 30) @Pattern(regexp = ProductRules.ATTRIBUTE_KEY_REGEX,
                message = "must not contain '.' or '$'") String,
                @NotBlank @Size(max = 50) String> attributes,

        @NotNull @Min(0) @Max(ProductRules.MAX_MONEY) Long price,

        @Min(0) @Max(ProductRules.MAX_MONEY) Long costPrice,

        @Min(0) @Max(ProductRules.MAX_LOW_STOCK_THRESHOLD) Integer lowStockThreshold) {
}
