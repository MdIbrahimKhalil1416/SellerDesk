package com.sellerdesk.order.service;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.order.dto.OrderResponse;
import com.sellerdesk.order.dto.OrderSummary;
import com.sellerdesk.order.model.Order;
import com.sellerdesk.order.model.OrderItem;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Order entity → response DTO. Controllers never see the entity (docs/ARCHITECTURE.md §2). */
@Component
@RequiredArgsConstructor
public class OrderMapper {

    private final CurrentUser currentUser;

    /**
     * @param available live {@code available} per variant id, or an empty map when the user may not
     *                  see stock or the order is no longer {@code PENDING}
     */
    public OrderResponse toResponse(Order o, Map<String, Integer> available) {
        List<OrderResponse.Item> items = o.getItems().stream().map(i -> toItem(i, available)).toList();
        List<OrderResponse.StatusChange> history = o.getStatusHistory().stream()
                .map(h -> new OrderResponse.StatusChange(h.from(), h.to(), h.at(),
                        new OrderResponse.Actor(h.by(), h.byName()), h.note()))
                .toList();
        OrderResponse.Shipping shipping = o.getShipping() == null ? null
                : new OrderResponse.Shipping(o.getShipping().courierName(), o.getShipping().trackingId());
        return new OrderResponse(o.getId(), o.displayNumber(), o.getStatus(), o.getSource(),
                new OrderResponse.Customer(o.getCustomerId(), o.getCustomer().name(), o.getCustomer().phone(),
                        AddressDto.from(o.getCustomer().address())),
                items, o.getSubtotal(), o.getDiscount(), o.getDeliveryCharge(), o.getTotal(), o.getAdvancePaid(),
                o.getCodAmount(), o.getNote(), shipping, o.getCancelReason(), o.getReturnReason(), history,
                allowedActions(o), new OrderResponse.Actor(o.getCreatedBy(), o.getCreatedByName()),
                o.getVersion() == null ? 0 : o.getVersion(), o.getCreatedAt(), o.getUpdatedAt());
    }

    /** A list row. District and zone come from the delivery address snapshot. */
    public static OrderSummary toSummary(Order o) {
        int units = o.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        var address = o.getCustomer() == null ? null : o.getCustomer().address();
        return new OrderSummary(o.getId(), o.displayNumber(), o.getStatus(), o.getSource(), o.getCustomerId(),
                o.getCustomer() == null ? null : o.getCustomer().name(),
                o.getCustomer() == null ? null : o.getCustomer().phone(),
                address == null ? null : address.district(), address == null ? null : address.zone(),
                units, o.getTotal(), o.getCodAmount(), o.getCreatedAt());
    }

    /** Actions possible from the current status that the current user has the permission for. */
    List<OrderAction> allowedActions(Order o) {
        return Arrays.stream(OrderAction.values())
                .filter(a -> a.allowedFrom(o.getStatus()))
                .filter(a -> currentUser.hasPermission(a.permission()))
                .toList();
    }

    private static OrderResponse.Item toItem(OrderItem i, Map<String, Integer> available) {
        return new OrderResponse.Item(i.getProductId(), i.getVariantId(), i.getSku(), i.getProductName(),
                i.getVariantName(), i.getUnitPrice(), i.getQuantity(), i.getLineTotal(), i.getReturnedRestocked(),
                available.get(i.getVariantId()));
    }
}
