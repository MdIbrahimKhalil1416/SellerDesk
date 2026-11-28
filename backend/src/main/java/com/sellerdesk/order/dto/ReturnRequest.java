package com.sellerdesk.order.dto;

import com.sellerdesk.order.service.OrderRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /orders/{id}/return}. {@code items} lists only exceptions: an item left out
 * goes back on the shelf ({@code restock = true}). Damaged items: {@code restock = false}.
 */
public record ReturnRequest(
        @Schema(example = "Customer refused the parcel") @NotBlank @Size(max = OrderRules.MAX_REASON) String reason,
        @Size(max = OrderRules.MAX_ITEMS) List<@NotNull @Valid Item> items) {

    public record Item(@NotBlank @Size(max = 24) String variantId, @NotNull Boolean restock) {
    }
}
