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
 * {@code POST /products/{id}/variants}: the same fields as {@link VariantRequest} plus the
 * product {@code version} the client loaded.
 */
public record AddVariantRequest(
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

        @Min(0) @Max(ProductRules.MAX_LOW_STOCK_THRESHOLD) Integer lowStockThreshold,

        @NotNull Long version) {

    public VariantRequest toVariantRequest() {
        return new VariantRequest(sku, name, attributes, price, costPrice, lowStockThreshold);
    }
}
