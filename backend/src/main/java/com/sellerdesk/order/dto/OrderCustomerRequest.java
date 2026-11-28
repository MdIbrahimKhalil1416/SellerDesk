package com.sellerdesk.order.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.phone.BdMobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A customer typed into the order form: found by phone, or created (docs/modules/CUSTOMER.md §5).
 * An existing customer's saved name is never changed by an order.
 *
 * @param name    required only for a new phone; the order shows this name either way
 * @param address optional; added to the customer if new, and the default delivery address
 */
public record OrderCustomerRequest(
        @Schema(example = "Rahim Uddin") @Size(max = 100) String name,
        @Schema(example = "01712345678") @NotBlank @BdMobile @Size(max = 30) String phone,
        @Valid AddressDto address) {
}
