package com.sellerdesk.order.service;

import com.sellerdesk.order.model.OrderStatus;

/**
 * Published inside the status-change transaction. Listeners that use
 * {@code @TransactionalEventListener(AFTER_COMMIT)} receive it only once the change is committed.
 *
 * <p>No listener yet. It is the hook for later features that must not block or roll back the
 * order (notifications, courier booking in Phase 2). Customer counters do <b>not</b> use it: they
 * are updated inside the transaction so they can never drift (docs/modules/ORDER.md §1).
 *
 * @param from {@code null} for a newly created order
 */
public record OrderStatusChangedEvent(String shopId, String orderId, OrderStatus from, OrderStatus to) {
}
