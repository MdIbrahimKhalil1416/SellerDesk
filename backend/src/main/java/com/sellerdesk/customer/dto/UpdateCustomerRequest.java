package com.sellerdesk.customer.dto;

import com.sellerdesk.common.phone.BdMobile;
import com.sellerdesk.customer.service.CustomerRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PUT /customers/{id}}: replaces the profile fields (an optional field left out is
 * cleared). Addresses have their own endpoints; counters are never accepted.
 *
 * @param version the value the client loaded; another value → 409 CONCURRENT_MODIFICATION
 */
public record UpdateCustomerRequest(
        @NotBlank @Size(max = CustomerRules.MAX_NAME) String name,
        @NotBlank @BdMobile String phone,
        @BdMobile String altPhone,
        @Size(max = 300) @Pattern(regexp = CustomerRules.URL_REGEX, message = "must be a http(s) URL") String facebookProfileUrl,
        @Size(max = CustomerRules.MAX_NOTE) String note,
        @Size(max = CustomerRules.MAX_TAGS, message = "must have at most 10 tags")
        List<@NotBlank @Size(max = CustomerRules.MAX_TAG) String> tags,
        @NotNull Long version) {
}
