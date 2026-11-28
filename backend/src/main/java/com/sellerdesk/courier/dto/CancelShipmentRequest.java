package com.sellerdesk.courier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancel a booking before pickup. */
public record CancelShipmentRequest(@NotBlank @Size(max = 500) String reason) {
}
