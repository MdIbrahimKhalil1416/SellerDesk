package com.sellerdesk.inventory.dto;

import com.sellerdesk.inventory.model.AdjustmentReason;
import com.sellerdesk.inventory.model.MovementType;
import java.time.Instant;

/** One entry of the stock movement log. Deltas are signed. */
public record MovementResponse(
        String id,
        String productId,
        String variantId,
        String sku,
        String variantName,
        MovementType type,
        int onHandDelta,
        int reservedDelta,
        int onHandAfter,
        int reservedAfter,
        int availableAfter,
        AdjustmentReason reasonCode,
        String note,
        String orderId,
        String orderNumber,
        Actor createdBy,
        Instant createdAt) {

    /** Who made the change (name is a snapshot). */
    public record Actor(String id, String name) {
    }
}
