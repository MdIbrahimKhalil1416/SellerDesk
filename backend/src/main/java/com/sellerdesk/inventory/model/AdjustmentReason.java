package com.sellerdesk.inventory.model;

/**
 * Why stock was corrected by hand. Preset codes (instead of free text) so reports can group
 * losses by cause; details go into the movement's {@code note}, which is required for {@link #OTHER}.
 */
public enum AdjustmentReason {
    DAMAGED,
    LOST,
    FOUND,
    COUNT_CORRECTION,
    OTHER
}
