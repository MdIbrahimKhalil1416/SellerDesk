package com.sellerdesk.order.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.order.model.OrderSource;
import com.sellerdesk.order.service.OrderRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /orders} (docs/modules/ORDER.md §8). Exactly one of {@code customerId} and
 * {@code customer}. The server computes every total; there is no field for them (unknown JSON
 * fields such as {@code total} or {@code shopId} are ignored).
 *
 * @param requestId       a new UUID per form; sending the same one again returns the first order
 * @param deliveryAddress optional; default: {@code customer.address}, else the customer's default address
 * @param deliveryCharge  poisha; optional, default from the shop settings by the address zone
 */
public record CreateOrderRequest(
        @NotNull @Pattern(regexp = OrderRules.UUID_REGEX, message = "must be a UUID") String requestId,
        @Size(max = 24) String customerId,
        @Valid OrderCustomerRequest customer,
        @Valid AddressDto deliveryAddress,
        @NotNull @Size(min = 1, max = OrderRules.MAX_ITEMS) List<@NotNull @Valid OrderItemRequest> items,
        @Schema(example = "6000") @Min(0) @Max(OrderRules.MAX_DELIVERY_CHARGE) Long deliveryCharge,
        @Min(0) @Max(OrderRules.MAX_MONEY) Long discount,
        @Min(0) @Max(OrderRules.MAX_MONEY) Long advancePaid,
        @NotNull OrderSource source,
        @Size(max = OrderRules.MAX_NOTE) String note) {
}
