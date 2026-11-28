package com.sellerdesk.order.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.service.OrderRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PUT /orders/{id}}, only while {@code PENDING}. A full replace of the editable fields: an
 * optional field left out goes back to its default ({@code deliveryCharge} by zone, money 0, note
 * cleared). The customer itself (id, phone) cannot change.
 *
 * @param customerName name shown on this order; left out keeps the current one
 * @param version      the version the form loaded; another change since → 409 CONCURRENT_MODIFICATION
 */
public record UpdateOrderRequest(
        @Size(max = 100) String customerName,
        @NotNull @Valid AddressDto deliveryAddress,
        @NotNull @Size(min = 1, max = OrderRules.MAX_ITEMS) List<@NotNull @Valid OrderItemRequest> items,
        @Min(0) @Max(OrderRules.MAX_DELIVERY_CHARGE) Long deliveryCharge,
        @Min(0) @Max(OrderRules.MAX_MONEY) Long discount,
        @Min(0) @Max(OrderRules.MAX_MONEY) Long advancePaid,
        @NotNull OrderSource source,
        @Size(max = OrderRules.MAX_NOTE) String note,
        @NotNull Long version) {
}
