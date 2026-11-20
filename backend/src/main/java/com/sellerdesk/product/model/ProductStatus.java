package com.sellerdesk.product.model;

public enum ProductStatus {
    /** Normal. Appears in the order form (lookup) if it has active variants. */
    ACTIVE,
    /** Retired. Still editable, but cannot be added to new orders. Can be unarchived. */
    ARCHIVED
}
