package com.sellerdesk.product.dto;

import com.sellerdesk.product.service.ProductRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PUT /products/{id}/images/order}: the product's image ids in the new order (same set,
 * nothing added or removed). The first one becomes the cover.
 */
public record ReorderImagesRequest(
        @NotNull @Size(max = ProductRules.MAX_IMAGES) List<@NotBlank String> imageIds,
        @NotNull Long version) {
}
