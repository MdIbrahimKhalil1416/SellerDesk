package com.sellerdesk.order.service;

import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.order.model.OrderStatus;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

/** Error codes of the Order module (docs/modules/ORDER.md §8). The frontend switches on them. */
public final class OrderErrors {

    /** Order id unknown <b>or of another shop</b>: the two look the same. */
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String ORDER_NOT_EDITABLE = "ORDER_NOT_EDITABLE";
    public static final String VARIANT_NOT_ORDERABLE = "VARIANT_NOT_ORDERABLE";
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String REQUEST_ID_REUSED = "REQUEST_ID_REUSED";

    private OrderErrors() {
    }

    public static ResourceNotFoundException orderNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Order not found");
    }

    public static BusinessRuleException invalidTransition(OrderStatus current, OrderAction action) {
        return new BusinessRuleException(INVALID_STATUS_TRANSITION,
                "Cannot " + action.name().toLowerCase(java.util.Locale.ROOT) + " an order that is " + current);
    }

    public static BusinessRuleException notEditable(OrderStatus current) {
        return new BusinessRuleException(ORDER_NOT_EDITABLE,
                "Items and money can only be changed while the order is PENDING (it is " + current + ")");
    }

    public static BusinessRuleException requestIdReused() {
        return new BusinessRuleException(REQUEST_ID_REUSED,
                "This requestId was already used for a different order. Send a new requestId");
    }

    /** Handled globally as {@code 409 CONCURRENT_MODIFICATION}. */
    public static OptimisticLockingFailureException staleVersion() {
        return new OptimisticLockingFailureException("Order was changed by someone else");
    }

    /** {@code 409 VARIANT_NOT_ORDERABLE}, one field error per archived product / inactive variant. */
    public static AppException notOrderable(List<ApiError.FieldError> fieldErrors) {
        return new OrderConflict(VARIANT_NOT_ORDERABLE,
                "Archived products and inactive variants cannot be added to an order", fieldErrors);
    }

    /** {@code 409 INSUFFICIENT_STOCK} listing every short item, not only the first. */
    public static AppException insufficientStock(String message, List<ApiError.FieldError> fieldErrors) {
        return new OrderConflict(INSUFFICIENT_STOCK, message, fieldErrors);
    }

    /** A 409 that points at fields (like {@code items[2].quantity}). */
    static final class OrderConflict extends AppException {
        OrderConflict(String code, String message, List<ApiError.FieldError> fieldErrors) {
            super(HttpStatus.CONFLICT, code, message, fieldErrors);
        }
    }
}
