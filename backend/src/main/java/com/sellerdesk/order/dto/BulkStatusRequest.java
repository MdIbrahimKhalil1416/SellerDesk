package com.sellerdesk.order.dto;

import com.sellerdesk.order.service.OrderAction;
import com.sellerdesk.order.service.OrderRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /orders/bulk-status}: the same action on up to {@value OrderRules#MAX_BULK} orders.
 * {@code RETURN} is not offered in bulk (it needs a restock choice per item).
 *
 * @param reason      required for {@code CANCEL}
 * @param courierName optional, for {@code SHIP}
 */
public record BulkStatusRequest(
        @Schema(example = "PACK") @NotNull OrderAction action,
        @NotNull @Size(min = 1, max = OrderRules.MAX_BULK) List<@NotBlank @Size(max = 24) String> orderIds,
        @Size(max = OrderRules.MAX_REASON) String note,
        @Size(max = OrderRules.MAX_REASON) String reason,
        @Size(max = 50) String courierName) {
}
