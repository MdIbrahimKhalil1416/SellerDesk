package com.sellerdesk.customer.dto;

import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.customer.model.CustomerStatus;
import java.time.Instant;
import java.util.List;

/** One customer with everything the detail page and the order form need. Money in poisha. */
public record CustomerResponse(
        String id,
        String name,
        String phone,
        String altPhone,
        String facebookProfileUrl,
        List<AddressResponse> addresses,
        String note,
        List<String> tags,
        CustomerStatus status,
        StatsResponse stats,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public record AddressResponse(String addressId, String label, String line, String area, String district,
                                  DeliveryZone zone, String note, boolean isDefault) {
    }

    /** @param totalSpent poisha, sum of delivered order totals */
    public record StatsResponse(int totalOrders, int deliveredOrders, int returnedOrders, int cancelledOrders,
                                long totalSpent, Instant lastOrderAt) {
    }
}
