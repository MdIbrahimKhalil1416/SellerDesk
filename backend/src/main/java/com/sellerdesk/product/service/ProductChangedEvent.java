package com.sellerdesk.product.service;

/**
 * Published after every product write (create, update, status, variant, image, delete).
 * {@link ProductLookupCacheEvictor} uses it to drop the shop's cached lookup list.
 */
public record ProductChangedEvent(String shopId, String productId) {
}
