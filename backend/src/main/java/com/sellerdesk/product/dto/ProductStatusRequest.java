package com.sellerdesk.product.dto;

import com.sellerdesk.product.model.ProductStatus;
import jakarta.validation.constraints.NotNull;

/** {@code PATCH /products/{id}/status}: archive ({@code ARCHIVED}) or unarchive ({@code ACTIVE}). */
public record ProductStatusRequest(@NotNull ProductStatus status, @NotNull Long version) {
}
