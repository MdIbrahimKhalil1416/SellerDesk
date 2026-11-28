package com.sellerdesk.customer.dto;

import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.customer.service.CustomerRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One customer address (add, replace, or part of create).
 *
 * @param zone      required: decides the default delivery charge (the form suggests it from the district)
 * @param isDefault {@code true} makes this the default address; {@code false}/missing keeps the current default
 */
@Schema(description = "A customer address")
public record AddressRequest(
        @Schema(example = "Home") @Size(max = CustomerRules.MAX_LABEL) String label,
        @Schema(example = "House 12, Road 5, Dhanmondi") @NotBlank @Size(max = 200) String line,
        @Schema(example = "Dhanmondi") @Size(max = 100) String area,
        @Schema(example = "Dhaka") @NotBlank @Size(max = 50) String district,
        @NotNull DeliveryZone zone,
        @Schema(example = "Opposite the mosque") @Size(max = 200) String note,
        Boolean isDefault) {
}
