package com.sellerdesk.product.model;

/**
 * Stock numbers of one variant. <b>Owned by the Inventory module</b>: the product module writes
 * the zeros on create and never changes them afterwards (docs/ARCHITECTURE.md §1).
 *
 * @param available {@code onHand - reserved}, kept in the document so it can be filtered and summed
 */
public record VariantStock(int onHand, int reserved, int available) {

    public static VariantStock empty() {
        return new VariantStock(0, 0, 0);
    }
}
