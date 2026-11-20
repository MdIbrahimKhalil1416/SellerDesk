package com.sellerdesk.product.dto;

import java.util.List;

/**
 * One product in the order-form picker ({@code GET /products/lookup}). Active products and active
 * variants only.
 *
 * <p>Cached per shop and shared by every user of that shop, so it must never hold anything that
 * depends on permissions (no stock, no cost price). Immutable, so a cached copy cannot be changed.
 */
public record ProductLookupItem(String productId, String name, String coverImageUrl, List<LookupVariant> variants) {

    public ProductLookupItem {
        variants = List.copyOf(variants);
    }

    /** @param price poisha */
    public record LookupVariant(String variantId, String sku, String name, long price) {
    }
}
