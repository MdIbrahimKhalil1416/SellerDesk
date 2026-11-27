package com.sellerdesk.auth.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.phone.BdMobile;
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

        @Schema(example = "01712345678", description = "Bangladeshi mobile: 01XXXXXXXXX, +8801XXXXXXXXX or 8801XXXXXXXXX; stored as 01XXXXXXXXX")
        @NotBlank @BdMobile
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
