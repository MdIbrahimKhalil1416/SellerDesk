package com.sellerdesk.order.dto;

import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One row of the order list. Money in poisha.
 *
 * @param itemCount number of units (sum of quantities), what the packer has to pick
 */
public record OrderSummary(
        String id,
        @Schema(example = "SD-1042") String orderNumber,
        OrderStatus status,
        OrderSource source,
        String customerId,
        String customerName,
        String customerPhone,
        String district,
        DeliveryZone zone,
        int itemCount,
        long total,
        long codAmount,
        Instant createdAt) {
}
