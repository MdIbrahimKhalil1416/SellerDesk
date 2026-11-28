package com.sellerdesk.order.model;

/** Courier info typed in at shipping (free text: courier integration is Phase 2). */
public record Shipping(String courierName, String trackingId) {
}
