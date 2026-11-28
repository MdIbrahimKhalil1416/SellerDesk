package com.sellerdesk.customer.model;

import java.time.Instant;

/**
 * Order counters of a customer, denormalized for the customer list. Changed <b>only</b> through
 * {@code CustomerService.record*} by the Order module, with {@code $inc} inside Order's
 * transaction; never accepted from a client.
 *
 * @param totalSpent  poisha, sum of delivered order totals
 * @param lastOrderAt newest order creation time, or {@code null}
 */
public record CustomerStats(int totalOrders, int deliveredOrders, int returnedOrders, int cancelledOrders,
                            long totalSpent, Instant lastOrderAt) {

    public static CustomerStats empty() {
        return new CustomerStats(0, 0, 0, 0, 0L, null);
    }
}
