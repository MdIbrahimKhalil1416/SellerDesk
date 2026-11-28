package com.sellerdesk.courier.dto;

import com.sellerdesk.courier.model.CourierCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Send a PACKED order to a courier. No {@code shopId}: the shop comes from the JWT.
 *
 * @param note instructions for the rider ("Call before delivery")
 */
public record SendShipmentRequest(
        @Schema(example = "MOCK") @NotNull CourierCode provider,
        @Size(max = 200) String note) {
}
