package com.sellerdesk.product.service;

/**
 * "Is this product used anywhere?" Asked before a product is deleted.
 *
 * <p>Defined here, <b>implemented by other modules</b>: Inventory (any stock movement) and Order
 * (any order item) add a {@code @Component} implementing it when they are built.
 * {@link ProductService} receives all implementations as a list (empty until then).
 *
 * <p>This is <i>dependency inversion</i>: the product module asks the question without knowing
 * who answers, so the dependency still points {@code order / inventory → product}
 * (docs/ARCHITECTURE.md §1), never the other way round.
 */
public interface ProductUsageChecker {

    /** @param shopId always the current tenant; implementations must filter by it */
    boolean isUsed(String shopId, String productId);
}
