package com.sellerdesk.inventory.service;

/** One order item for the order-driven operations: {@code quantity} units of a variant (≥ 1). */
public record StockLine(String variantId, int quantity) {
}
