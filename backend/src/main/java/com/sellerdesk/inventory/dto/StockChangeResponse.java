package com.sellerdesk.inventory.dto;

/** Answer of restock and adjustment: the movement written and the stock right after it. */
public record StockChangeResponse(MovementResponse movement, StockLevels stock) {
}
