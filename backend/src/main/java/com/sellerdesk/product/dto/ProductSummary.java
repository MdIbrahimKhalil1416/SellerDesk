package com.sellerdesk.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sellerdesk.product.model.ProductStatus;
import java.time.Instant;

/**
 * One row of the product list.
 *
 * @param priceRange     poisha over active variants; both {@code null} if no variant is active
 * @param totalAvailable sum of {@code available} over all variants; only with STOCK_VIEW
 * @param coverImageUrl  first image, or {@code null}
 */
public record ProductSummary(
        String id,
        String name,
        String category,
        ProductStatus status,
        int variantCount,
        PriceRange priceRange,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer totalAvailable,
        String coverImageUrl,
        Instant updatedAt) {

    public record PriceRange(Long min, Long max) {
    }
}
