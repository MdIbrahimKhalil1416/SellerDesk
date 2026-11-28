package com.sellerdesk.order.service;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.order.model.OrderStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * The status machine in one table (docs/modules/ORDER.md §4): each action, the statuses it may
 * start from, where it leads, and the permission it needs. Any other move is
 * {@code 409 INVALID_STATUS_TRANSITION}.
 *
 * <p>Stock and customer side effects are in {@code OrderService#applySideEffects}, next to the
 * code that changes the status, so both happen in the same transaction.
 */
public enum OrderAction {
    /** Reserves stock. */
    CONFIRM(EnumSet.of(OrderStatus.PENDING), OrderStatus.CONFIRMED, Permission.ORDER_CONFIRM),
    PACK(EnumSet.of(OrderStatus.CONFIRMED), OrderStatus.PACKED, Permission.ORDER_FULFILL),
    /** Deducts stock: the units leave the shelf. */
    SHIP(EnumSet.of(OrderStatus.PACKED), OrderStatus.SHIPPED, Permission.ORDER_FULFILL),
    DELIVER(EnumSet.of(OrderStatus.SHIPPED), OrderStatus.DELIVERED, Permission.ORDER_CLOSE),
    /** Puts the chosen items back on the shelf. Only from SHIPPED: DELIVERED is final. */
    RETURN(EnumSet.of(OrderStatus.SHIPPED), OrderStatus.RETURNED, Permission.ORDER_CLOSE),
    /** Releases reserved stock (if any). Not from SHIPPED: the parcel is with the courier, use RETURN. */
    CANCEL(EnumSet.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PACKED), OrderStatus.CANCELLED,
            Permission.ORDER_CANCEL);

    private final Set<OrderStatus> from;
    private final OrderStatus to;
    private final Permission permission;

    OrderAction(Set<OrderStatus> from, OrderStatus to, Permission permission) {
        this.from = Set.copyOf(from);
        this.to = to;
        this.permission = permission;
    }

    public boolean allowedFrom(OrderStatus status) {
        return from.contains(status);
    }

    public OrderStatus to() {
        return to;
    }

    public Permission permission() {
        return permission;
    }
}
