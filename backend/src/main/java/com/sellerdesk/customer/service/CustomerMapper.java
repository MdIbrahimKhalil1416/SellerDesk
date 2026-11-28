package com.sellerdesk.customer.service;

import com.sellerdesk.customer.dto.CustomerResponse;
import com.sellerdesk.customer.dto.CustomerSummary;
import com.sellerdesk.customer.model.Customer;
import com.sellerdesk.customer.model.CustomerAddress;
import com.sellerdesk.customer.model.CustomerStats;
import java.util.List;

/** Entity → DTO. Controllers never see {@link Customer}; internal fields (search keys, revisions) stay inside. */
public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(c.getId(), c.getName(), c.getPhone(), c.getAltPhone(), c.getFacebookProfileUrl(),
                c.getAddresses().stream().map(CustomerMapper::toAddress).toList(), c.getNote(),
                List.copyOf(c.getTags()), c.getStatus(), toStats(c.getStats()), c.getVersion(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    public static CustomerSummary toSummary(Customer c) {
        CustomerSummary.DefaultAddress address = defaultAddress(c)
                .map(a -> new CustomerSummary.DefaultAddress(a.getDistrict(), a.getArea(), a.getZone()))
                .orElse(null);
        return new CustomerSummary(c.getId(), c.getName(), c.getPhone(), c.getAltPhone(), address,
                List.copyOf(c.getTags()), c.getStatus(), toStats(c.getStats()), c.getCreatedAt());
    }

    public static CustomerResponse.AddressResponse toAddress(CustomerAddress a) {
        return new CustomerResponse.AddressResponse(a.getAddressId(), a.getLabel(), a.getLine(), a.getArea(),
                a.getDistrict(), a.getZone(), a.getNote(), a.isDefault());
    }

    static java.util.Optional<CustomerAddress> defaultAddress(Customer c) {
        return c.getAddresses().stream().filter(CustomerAddress::isDefault).findFirst()
                .or(() -> c.getAddresses().stream().findFirst());
    }

    private static CustomerResponse.StatsResponse toStats(CustomerStats s) {
        CustomerStats stats = s != null ? s : CustomerStats.empty();
        return new CustomerResponse.StatsResponse(stats.totalOrders(), stats.deliveredOrders(), stats.returnedOrders(),
                stats.cancelledOrders(), stats.totalSpent(), stats.lastOrderAt());
    }
}
