package com.sellerdesk.inventory.service;

/** Where the low-stock threshold of a variant comes from. */
public enum ThresholdSource {
    /** The variant has its own {@code lowStockThreshold}. */
    VARIANT,
    /** The shop's {@code settings.defaultLowStockThreshold}. */
    SHOP
}
