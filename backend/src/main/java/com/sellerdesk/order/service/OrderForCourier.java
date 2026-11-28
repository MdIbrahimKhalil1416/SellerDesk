package com.sellerdesk.order.service;

import com.sellerdesk.common.model.Address;
import com.sellerdesk.order.model.OrderStatus;

/**
 * What the courier module may read about an order to book it (docs/modules/COURIER.md §3).
 * Read-only: the courier module never writes orders.
 *
 * @param orderNumber display number, {@code SD-1042}
 * @param codAmount   poisha, what the courier collects (frozen since CONFIRMED)
 * @param itemCount   units (sum of quantities), for the parcel description
 */
public record OrderForCourier(String id, String orderNumber, OrderStatus status, long codAmount,
                              String customerName, String customerPhone, Address address, int itemCount) {
}
