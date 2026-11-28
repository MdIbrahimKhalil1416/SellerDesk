package com.sellerdesk.inventory.dto;

import com.sellerdesk.inventory.service.StockStatus;
import com.sellerdesk.inventory.service.ThresholdSource;
import com.sellerdesk.product.model.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One variant in the stock list.
 *
 * @param lowStockThreshold the threshold that applies: the variant's own, or the shop default
 * @param thresholdSource   where {@code lowStockThreshold} comes from
 */
@Schema(description = "Stock of one variant")
public record StockRow(
        String productId,
        String productName,
        String coverImageUrl,
        ProductStatus productStatus,
        String variantId,
        String sku,
        String variantName,
        boolean active,
        int onHand,
        int reserved,
        int available,
        int lowStockThreshold,
        ThresholdSource thresholdSource,
        StockStatus stockStatus) {
}
