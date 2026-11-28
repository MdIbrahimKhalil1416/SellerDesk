package com.sellerdesk.order.service;

/**
 * "Is this order booked with a courier right now?" (docs/modules/COURIER.md §6, §7).
 *
 * <p>Defined here, implemented by the {@code courier} module, so {@code order} never depends on
 * {@code courier} (same pattern as {@code ProductUsageChecker}). While a shipment is active the
 * order cannot be shipped or cancelled by hand: the courier could otherwise pick up a parcel for a
 * cancelled order, or the stock would be deducted twice.
 */
public interface ShipmentGuard {

    boolean hasActiveShipment(String shopId, String orderId);
}
