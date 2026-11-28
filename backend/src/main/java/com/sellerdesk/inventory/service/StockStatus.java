package com.sellerdesk.inventory.service;

/** Stock status of a variant, from {@code available} and its threshold ({@link StockStatusCalculator}). */
public enum StockStatus {
    IN_STOCK,
    LOW,
    OUT
}
