package com.sellerdesk.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.model.OrderStatus;
import com.sellerdesk.order.service.OrderAction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * One order with everything the order page shows. Money in poisha.
 *
 * @param orderNumber    display form, e.g. {@code SD-1042}
 * @param allowedActions what the current user may do now (status and permissions); for the buttons
 */
public record OrderResponse(
        String id,
        @Schema(example = "SD-1042") String orderNumber,
        OrderStatus status,
        OrderSource source,
        Customer customer,
        List<Item> items,
        long subtotal,
        long discount,
        long deliveryCharge,
        long total,
        long advancePaid,
        long codAmount,
        String note,
        Shipping shipping,
        String cancelReason,
        String returnReason,
        List<StatusChange> statusHistory,
        List<OrderAction> allowedActions,
        Actor createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record Customer(String id, String name, String phone, AddressDto address) {
    }

    /**
     * @param available live stock of the variant; only with {@code STOCK_VIEW} and only while the
     *                  order is {@code PENDING} (after confirm the units are reserved), else {@code null}
     */
    public record Item(String productId, String variantId, String sku, String productName, String variantName,
                       long unitPrice, int quantity, long lineTotal, Boolean returnedRestocked,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer available) {
    }

    public record Shipping(String courierName, String trackingId) {
    }

    public record StatusChange(OrderStatus from, OrderStatus to, Instant at, Actor by, String note) {
    }

    public record Actor(String id, String name) {
    }
}
