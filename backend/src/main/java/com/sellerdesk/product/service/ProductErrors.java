package com.sellerdesk.product.service;

import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Error codes of the product module (docs/modules/PRODUCT.md §6) and small factories for the
 * errors that need no own class. The frontend switches on these codes.
 */
public final class ProductErrors {

    /** Product, variant or image id unknown <b>or from another shop</b>: the two look the same. */
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String SKU_ALREADY_EXISTS = "SKU_ALREADY_EXISTS";
    public static final String DUPLICATE_VARIANT_ATTRIBUTES = "DUPLICATE_VARIANT_ATTRIBUTES";
    public static final String VARIANT_LIMIT_REACHED = "VARIANT_LIMIT_REACHED";
    public static final String IMAGE_LIMIT_REACHED = "IMAGE_LIMIT_REACHED";
    public static final String PRODUCT_IN_USE = "PRODUCT_IN_USE";

    private ProductErrors() {
    }

    public static ResourceNotFoundException productNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Product not found");
    }

    public static ResourceNotFoundException variantNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Variant not found");
    }

    public static ResourceNotFoundException imageNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Image not found");
    }

    public static BusinessRuleException variantLimitReached() {
        return new BusinessRuleException(VARIANT_LIMIT_REACHED,
                "A product can have at most " + ProductRules.MAX_VARIANTS + " variants");
    }

    public static BusinessRuleException imageLimitReached() {
        return new BusinessRuleException(IMAGE_LIMIT_REACHED,
                "A product can have at most " + ProductRules.MAX_IMAGES + " images");
    }

    public static BusinessRuleException duplicateAttributes(String variantName) {
        return new BusinessRuleException(DUPLICATE_VARIANT_ATTRIBUTES,
                "Two active variants have the same attributes (" + variantName + ")");
    }

    /** Handled globally as {@code 409 CONCURRENT_MODIFICATION}. */
    public static OptimisticLockingFailureException staleVersion() {
        return new OptimisticLockingFailureException("Product was changed by someone else");
    }
}
