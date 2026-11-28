package com.sellerdesk.inventory.service;

/**
 * The low-stock rules in one place (docs/modules/INVENTORY.md §1). Pure functions, no state.
 *
 * <ul>
 *   <li>Threshold: the variant's own {@code lowStockThreshold}, or the shop's
 *       {@code defaultLowStockThreshold} when the variant has none.</li>
 *   <li>Status, based on {@code available} (reserved units cannot be sold again):
 *       {@code OUT} if {@code available <= 0}, {@code LOW} if {@code 0 < available <= threshold},
 *       else {@code IN_STOCK}. A threshold of 0 therefore warns only when sold out.</li>
 * </ul>
 * The stock list aggregation in {@code StockRepository} filters with the same rules.
 */
public final class StockStatusCalculator {

    private StockStatusCalculator() {
    }

    public static int effectiveThreshold(Integer variantThreshold, int shopDefault) {
        return variantThreshold != null ? variantThreshold : shopDefault;
    }

    public static ThresholdSource source(Integer variantThreshold) {
        return variantThreshold != null ? ThresholdSource.VARIANT : ThresholdSource.SHOP;
    }

    public static StockStatus status(int available, int threshold) {
        if (available <= 0) {
            return StockStatus.OUT;
        }
        return available <= threshold ? StockStatus.LOW : StockStatus.IN_STOCK;
    }
}
