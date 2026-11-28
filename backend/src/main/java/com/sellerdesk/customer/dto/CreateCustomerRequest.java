package com.sellerdesk.customer.dto;

import com.sellerdesk.common.phone.BdMobile;
import com.sellerdesk.customer.service.CustomerRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /customers}. No {@code shopId}, no {@code stats}, no {@code status}: the server sets
 * them; unknown fields are ignored. Phones in any accepted Bangladeshi format; stored as
 * {@code 01XXXXXXXXX}.
 */
@Schema(description = "A new customer")
public record CreateCustomerRequest(
        @Schema(example = "Rahima Begum") @NotBlank @Size(max = CustomerRules.MAX_NAME) String name,
        @Schema(example = "01712345678") @NotBlank @BdMobile String phone,
        @Schema(example = "01812345678") @BdMobile String altPhone,
        @Size(max = 300) @Pattern(regexp = CustomerRules.URL_REGEX, message = "must be a http(s) URL") String facebookProfileUrl,
        @Size(max = CustomerRules.MAX_NOTE) String note,
        @Size(max = CustomerRules.MAX_TAGS, message = "must have at most 10 tags")
        List<@NotBlank @Size(max = CustomerRules.MAX_TAG) String> tags,
        @Size(max = CustomerRules.MAX_ADDRESSES, message = "must have at most 5 addresses")
        List<@Valid AddressRequest> addresses) {
}
