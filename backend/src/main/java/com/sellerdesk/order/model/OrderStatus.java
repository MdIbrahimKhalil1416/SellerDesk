package com.sellerdesk.order.model;

/**
 * Where an order is in the pipeline (docs/modules/ORDER.md §4).
 *
 * <pre>
 * PENDING → CONFIRMED → PACKED → SHIPPED → DELIVERED
 *    └──────────┴─────────┴→ CANCELLED      └→ RETURNED
 * </pre>
 *
 * {@code DELIVERED}, {@code CANCELLED} and {@code RETURNED} are final. The allowed moves are in
 * {@link com.sellerdesk.order.service.OrderAction}.
 */
public enum OrderStatus {
    /** Taken, nothing reserved yet. Fully editable. */
    PENDING,
    /** Stock reserved. Only address and note can change. */
    CONFIRMED,
    /** Packed, stock still reserved. */
    PACKED,
    /** With the courier: the units left the shelf (stock deducted). */
    SHIPPED,
    DELIVERED,
    CANCELLED,
    /** The courier brought it back; the chosen items went back on the shelf. */
    RETURNED
}
