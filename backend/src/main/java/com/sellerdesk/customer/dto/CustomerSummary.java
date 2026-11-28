package com.sellerdesk.customer.dto;

import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.customer.model.CustomerStatus;
import java.time.Instant;
import java.util.List;

/** One row of the customer list. */
public record CustomerSummary(
        String id,
        String name,
        String phone,
        String altPhone,
        DefaultAddress defaultAddress,
        List<String> tags,
        CustomerStatus status,
        CustomerResponse.StatsResponse stats,
        Instant createdAt) {

    /** The parts of the default address the list shows; {@code null} if the customer has none. */
    public record DefaultAddress(String district, String area, DeliveryZone zone) {
    }
}
