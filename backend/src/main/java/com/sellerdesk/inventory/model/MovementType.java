package com.sellerdesk.inventory.model;

/**
 * What caused a stock change (docs/modules/INVENTORY.md §3). Stored as the enum name.
 *
 * <ul>
 *   <li>{@link #INITIAL}: first stock-in of a variant (chosen automatically).</li>
 *   <li>{@link #RESTOCK}: every later stock-in (purchase, supplier delivery).</li>
 *   <li>{@link #ADJUSTMENT}: manual correction, up or down, with a reason code.</li>
 *   <li>{@link #RESERVE} / {@link #RELEASE} / {@link #DEDUCT} / {@link #RETURN}: caused by order
 *       status changes; never through an HTTP endpoint of this module.</li>
 * </ul>
 */
public enum MovementType {
    INITIAL,
    RESTOCK,
    ADJUSTMENT,
    RESERVE,
    RELEASE,
    DEDUCT,
    RETURN
}
