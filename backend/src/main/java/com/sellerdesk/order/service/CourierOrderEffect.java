package com.sellerdesk.order.service;

/**
 * What a courier status means for the order (docs/modules/COURIER.md §5).
 *
 * <ul>
 *   <li>{@link #SHIP}: the courier has the parcel (picked up / in transit): {@code PACKED → SHIPPED}
 *       with stock {@code DEDUCT}. Nothing if the order is already shipped or closed.</li>
 *   <li>{@link #DELIVER}: delivered: {@code SHIPPED → DELIVERED}; from {@code PACKED} it ships first
 *       (pickup webhook lost), both in the caller's transaction.</li>
 * </ul>
 */
public enum CourierOrderEffect {
    SHIP,
    DELIVER;

    /** What happened to the order. Never an exception for "not applicable": the courier's view is a fact. */
    public enum Result {
        /** The order moved (one or two steps). */
        APPLIED,
        /** The order was already there (e.g. delivered by hand before the webhook came). */
        ALREADY_DONE,
        /** The order is in a status where this does not apply (e.g. CANCELLED); left unchanged. */
        NOT_APPLICABLE
    }
}
