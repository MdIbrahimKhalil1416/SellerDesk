package com.sellerdesk.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /products/{id}}: product fields only (variants and images have their own endpoints).
 * A {@code null} description or category clears it.
 *
 * @param version the value the client loaded; a mismatch gives {@code 409 CONCURRENT_MODIFICATION}
 */
public record UpdateProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @Size(max = 50) String category,
        @NotNull Long version) {
}
