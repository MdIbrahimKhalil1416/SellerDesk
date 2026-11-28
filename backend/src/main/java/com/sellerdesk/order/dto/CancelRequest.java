package com.sellerdesk.order.dto;

import com.sellerdesk.order.service.OrderRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /orders/{id}/cancel}: the reason is required. */
public record CancelRequest(
        @Schema(example = "Customer stopped answering") @NotBlank @Size(max = OrderRules.MAX_REASON) String reason) {
}
