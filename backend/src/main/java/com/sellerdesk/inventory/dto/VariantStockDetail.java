package com.sellerdesk.inventory.dto;

import java.util.List;

/** {@code GET /inventory/variants/{variantId}}: the stock row and the newest movements. */
public record VariantStockDetail(StockRow row, List<MovementResponse> recentMovements) {
}
