package com.sellerdesk.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.service.OrderAction;
import java.util.List;

/**
 * Result of a bulk status change: one entry per requested order, in request order. A failed order
 * does not stop the others.
 */
public record BulkStatusResponse(OrderAction action, int succeeded, int failed, List<Result> results) {

    /**
     * @param ok        true also when the order already had the target status (nothing to do)
     * @param errorCode e.g. {@code INVALID_STATUS_TRANSITION}, {@code INSUFFICIENT_STOCK},
     *                  {@code NOT_FOUND} (unknown id, or an order of another shop)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String orderId, String orderNumber, boolean ok, OrderStatus status, String errorCode,
                         String message) {
    }
}
