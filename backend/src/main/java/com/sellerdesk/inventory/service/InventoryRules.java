package com.sellerdesk.inventory.service;

/** Limits of the Inventory module (docs/modules/INVENTORY.md §1, §6). */
public final class InventoryRules {

    /** Units moved by one movement, in absolute value. */
    public static final int MAX_QUANTITY = 100_000;

    /** Highest allowed {@code onHand}. Stock fields are Int32; this keeps every sum far from overflow. */
    public static final int MAX_ON_HAND = 1_000_000;

    public static final int MAX_NOTE = 200;

    /** Movements shown on the variant detail. */
    public static final int RECENT_MOVEMENTS = 10;

    /** A UUID in its usual text form (any version). */
    public static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private InventoryRules() {
    }
}
