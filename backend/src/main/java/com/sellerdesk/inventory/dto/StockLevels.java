package com.sellerdesk.inventory.dto;

/** The three stock numbers of a variant. {@code available = onHand - reserved}. */
public record StockLevels(int onHand, int reserved, int available) {
}
