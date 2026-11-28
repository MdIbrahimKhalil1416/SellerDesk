package com.sellerdesk.product.service;

import com.sellerdesk.product.model.ProductStatus;

/**
 * A variant as the Order module sees it when an item is added: the values it copies into the
 * order (snapshot) and the two flags that decide whether it may be sold.
 *
 * @param price selling price in poisha, as it is now
 */
public record OrderableVariant(String productId, String productName, ProductStatus productStatus,
                               String variantId, String sku, String variantName, long price, boolean active) {

    /** Active variant of an active product. Archived / inactive ones cannot be added to orders. */
    public boolean orderable() {
        return active && productStatus == ProductStatus.ACTIVE;
    }
}
