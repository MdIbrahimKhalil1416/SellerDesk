package com.sellerdesk.auth.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.util.Normalize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * New seller signs up. No {@code shopId}, {@code role} or {@code status} fields: the server
 * decides those, so a client cannot make itself an admin or pick another shop.
 */
@Schema(description = "Register a new shop and its owner")
public record RegisterShopRequest(
        @Schema(example = "Rahim Uddin")
        @NotBlank @Size(min = 2, max = 100) String ownerName,

        @Schema(example = "rahim@example.com")
        @NotBlank @Email @Size(max = 254) String email,

        @Schema(example = "rahim2026shop", description = "8-72 characters, at least one letter and one digit")
        @ValidPassword String password,

        @Schema(example = "Rahim Fashion")
        @NotBlank @Size(min = 2, max = 100) String shopName,

        @Schema(example = "01712345678", description = "Bangladeshi mobile, optional +88 prefix")
        @NotBlank @Pattern(regexp = Normalize.BD_MOBILE_INPUT_REGEX, message = "must be a Bangladeshi mobile number like 01712345678")
        String shopPhone,

        @Valid AddressDto address,

        @Schema(example = "https://facebook.com/rahimfashion")
        @Size(max = 300) @Pattern(regexp = "^https?://\\S+$", message = "must be a http(s) URL")
        String facebookPageUrl) {

    /** Never print the password, even in a debug log of the request. */
    @Override
    public String toString() {
        return "RegisterShopRequest[email=" + email + ", shopName=" + shopName + "]";
    }
}
