package com.sellerdesk.inventory.dto;

import com.sellerdesk.inventory.service.InventoryRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /inventory/variants/{variantId}/restock}. The server decides the type (first stock-in
 * of the variant → INITIAL, else RESTOCK); there is no {@code shopId}, {@code productId} or
 * {@code type} field, and unknown fields are ignored.
 *
 * @param requestId a new UUID per form submit; a repeat with the same id returns the first result
 */
@Schema(description = "Add stock to a variant")
public record RestockRequest(
        @Schema(example = "10", description = "Units added, 1 to 100000")
        @NotNull @Min(1) @Max(InventoryRules.MAX_QUANTITY) Integer quantity,

        @Schema(example = "Supplier delivery #12")
        @Size(max = InventoryRules.MAX_NOTE) String note,

        @Schema(example = "3f2c1e9a-8b7d-4c6e-9f00-1a2b3c4d5e6f")
        @NotNull @Pattern(regexp = InventoryRules.UUID_REGEX, message = "must be a UUID") String requestId) {
}
