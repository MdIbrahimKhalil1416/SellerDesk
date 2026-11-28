package com.sellerdesk.courier.dto;

import com.sellerdesk.courier.model.CourierCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Send many PACKED orders. Max 20 per request: each order may wait up to 10 s for the courier, so
 * the frontend sends bigger batches as several requests (docs/modules/COURIER.md §4).
 */
public record BulkShipmentRequest(
        @NotNull CourierCode provider,
        @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 30) String> orderIds) {
}
