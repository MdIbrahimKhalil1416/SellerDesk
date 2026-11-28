package com.sellerdesk.inventory.dto;

import com.sellerdesk.inventory.model.AdjustmentReason;
import com.sellerdesk.inventory.service.InventoryRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /inventory/variants/{variantId}/adjustments}: a manual correction.
 * {@code quantityChange} is signed ({@code -3} removes three units) and must not be 0 (checked by
 * the service). A decrease may only remove unreserved units ({@code available}).
 *
 * @param note required when {@code reasonCode} is {@code OTHER}
 */
@Schema(description = "Correct the stock of a variant by hand")
public record AdjustmentRequest(
        @Schema(example = "-2", description = "Signed change, -100000 to 100000, not 0")
        @NotNull @Min(-InventoryRules.MAX_QUANTITY) @Max(InventoryRules.MAX_QUANTITY) Integer quantityChange,

        @Schema(example = "DAMAGED")
        @NotNull AdjustmentReason reasonCode,

        @Schema(example = "Water damage in the store room")
        @Size(max = InventoryRules.MAX_NOTE) String note,

        @NotNull @Pattern(regexp = InventoryRules.UUID_REGEX, message = "must be a UUID") String requestId) {
}
