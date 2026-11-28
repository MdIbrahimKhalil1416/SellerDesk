package com.sellerdesk.order.dto;

import com.sellerdesk.order.service.OrderRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One line of the order form.
 *
 * @param unitPrice poisha; optional. Left out: the variant's current price (new item) or the price
 *                  already stored in the order (existing item on edit).
 */
public record OrderItemRequest(
        @NotBlank @Size(max = 24) String variantId,
        @NotNull @Min(1) @Max(OrderRules.MAX_QUANTITY) Integer quantity,
        @Schema(example = "45000") @Min(0) @Max(OrderRules.MAX_UNIT_PRICE) Long unitPrice) {
}
