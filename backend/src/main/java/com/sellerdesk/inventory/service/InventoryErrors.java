package com.sellerdesk.inventory.service;

import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.ResourceNotFoundException;

/** Error codes of the Inventory module (docs/modules/INVENTORY.md §5). The frontend switches on them. */
public final class InventoryErrors {

    /** Variant id unknown <b>or from another shop</b>: the two look the same. */
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String STOCK_LIMIT_EXCEEDED = "STOCK_LIMIT_EXCEEDED";
    public static final String REQUEST_ID_REUSED = "REQUEST_ID_REUSED";

    private InventoryErrors() {
    }

    public static ResourceNotFoundException variantNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Variant not found");
    }

    /** e.g. "Not enough stock for SKU TEE-RED-M: requested 3, available 1". */
    public static BusinessRuleException insufficientStock(String sku, int requested, int available) {
        return new BusinessRuleException(INSUFFICIENT_STOCK,
                "Not enough stock for SKU " + sku + ": requested " + requested + ", available " + available);
    }

    public static BusinessRuleException stockLimitExceeded(String sku) {
        return new BusinessRuleException(STOCK_LIMIT_EXCEEDED,
                "SKU " + sku + " cannot have more than " + InventoryRules.MAX_ON_HAND + " units on hand");
    }

    public static BusinessRuleException requestIdReused() {
        return new BusinessRuleException(REQUEST_ID_REUSED,
                "This requestId was already used for a different stock change. Send a new requestId");
    }
}
