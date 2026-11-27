package com.sellerdesk.shop.model;

/**
 * Per-shop settings, embedded in the shop document (docs/SCHEMA.md §2).
 *
 * <p>A record is immutable, so one instance can be cached and shared between threads safely
 * ({@code ShopSettingsLookup}).
 *
 * @param deliveryChargeInsideDhaka  poisha (1 BDT = 100 poisha)
 * @param deliveryChargeOutsideDhaka poisha
 * @param defaultLowStockThreshold   used when a variant has no own threshold
 * @param orderNumberPrefix          2-6 uppercase letters/digits, shown before the order number
 *                                   ({@code SD-1042}). Orders copy it when they are created, so a
 *                                   change only affects future orders.
 */
public record ShopSettings(
        long deliveryChargeInsideDhaka,
        long deliveryChargeOutsideDhaka,
        int defaultLowStockThreshold,
        String orderNumberPrefix) {

    public static final String DEFAULT_ORDER_NUMBER_PREFIX = "SD";

    /**
     * Shops created before {@code orderNumberPrefix} existed have no such field in MongoDB.
     * Spring Data builds this record through its constructor, passing {@code null} for the missing
     * field, so the default is filled in here, on read. No data migration is needed; the next
     * settings update writes the field.
     */
    public ShopSettings {
        if (orderNumberPrefix == null || orderNumberPrefix.isBlank()) {
            orderNumberPrefix = DEFAULT_ORDER_NUMBER_PREFIX;
        }
    }

    /** ৳60 inside Dhaka, ৳120 outside, low stock at 5 units, prefix {@code SD} (docs/SCHEMA.md §2). */
    public static ShopSettings defaults() {
        return new ShopSettings(6_000, 12_000, 5, DEFAULT_ORDER_NUMBER_PREFIX);
    }
}
