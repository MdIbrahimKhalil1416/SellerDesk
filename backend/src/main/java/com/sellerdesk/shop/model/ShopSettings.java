package com.sellerdesk.shop.model;

/**
 * Per-shop settings, embedded in the shop document.
 *
 * @param deliveryChargeInsideDhaka  poisha (1 BDT = 100 poisha)
 * @param deliveryChargeOutsideDhaka poisha
 * @param defaultLowStockThreshold   used when a variant has no own threshold
 */
public record ShopSettings(
        long deliveryChargeInsideDhaka,
        long deliveryChargeOutsideDhaka,
        int defaultLowStockThreshold) {

    /** ৳60 inside Dhaka, ৳120 outside, low stock at 5 units (docs/SCHEMA.md §2). */
    public static ShopSettings defaults() {
        return new ShopSettings(6_000, 12_000, 5);
    }
}
