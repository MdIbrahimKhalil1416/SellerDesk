package com.sellerdesk.order.dto;

import com.sellerdesk.order.service.OrderRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /orders/{id}/ship}; everything optional, the body may be left out. */
public record ShipRequest(
        @Schema(example = "Steadfast") @Size(max = 50) String courierName,
        @Size(max = 100) String trackingId,
        @Size(max = OrderRules.MAX_REASON) String note) {
}
