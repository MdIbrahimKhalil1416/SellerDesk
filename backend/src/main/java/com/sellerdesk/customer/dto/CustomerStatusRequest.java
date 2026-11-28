package com.sellerdesk.customer.dto;

import com.sellerdesk.customer.model.CustomerStatus;
import jakarta.validation.constraints.NotNull;

/** {@code PATCH /customers/{id}/status}: archive ({@code ARCHIVED}) or restore ({@code ACTIVE}). */
public record CustomerStatusRequest(@NotNull CustomerStatus status, @NotNull Long version) {
}
