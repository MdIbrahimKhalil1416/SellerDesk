package com.sellerdesk.shop.service;

/** Limits for shop settings (docs/API.md §2). Used by the request DTO annotations. */
public final class ShopRules {

    /**
     * ৳5,000 in poisha. Bangladeshi couriers charge roughly ৳60-৳150 per parcel, more for heavy
     * items; anything above ৳5,000 is almost certainly a typo (e.g. taka typed into a poisha field
     * twice). Prevents an order from silently getting a huge delivery charge.
     */
    public static final long MAX_DELIVERY_CHARGE = 500_000L;

    /** Same limit as a variant's own threshold (ProductRules.MAX_LOW_STOCK_THRESHOLD). */
    public static final int MAX_LOW_STOCK_THRESHOLD = 100_000;

    /** 2-6 letters or digits. Lowercase is accepted and stored in uppercase. */
    public static final String ORDER_NUMBER_PREFIX_REGEX = "^[A-Za-z0-9]{2,6}$";

    public static final String ORDER_NUMBER_PREFIX_MESSAGE = "must be 2 to 6 letters (A-Z) or digits, e.g. SD or RF24";

    private ShopRules() {
    }
}
