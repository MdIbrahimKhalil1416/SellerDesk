package com.sellerdesk.shop.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.phone.BdMobile;
import com.sellerdesk.shop.service.ShopRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Replaces the shop profile and settings ({@code PUT}: every field is sent; a missing optional
 * field is cleared). No {@code shopId}: the shop is always the logged-in user's.
 * The logo has its own endpoints ({@code PUT/DELETE /shop/logo}), because it is a file upload.
 */
@Schema(description = "Shop profile and settings")
public record UpdateShopRequest(
        @Schema(example = "Rahim Fashion")
        @NotBlank @Size(min = 2, max = 100) String name,

        @Schema(example = "01712345678", description = "Bangladeshi mobile: 01XXXXXXXXX, +8801XXXXXXXXX or 8801XXXXXXXXX; stored as 01XXXXXXXXX")
        @NotBlank @BdMobile String phone,

        @Schema(example = "hello@rahimfashion.com", description = "Business contact email (optional)")
        @Email @Size(max = 254) String email,

        @Valid AddressDto address,

        @Schema(example = "https://facebook.com/rahimfashion")
        @Size(max = 300) @Pattern(regexp = "^https?://\\S+$", message = "must be a http(s) URL")
        String facebookPageUrl,

        @NotNull @Valid Settings settings) {

    /**
     * Money in poisha (1 BDT = 100 poisha). Wrapper types ({@code Long}, not {@code long}) so a
     * missing field is a clear "must not be null" error instead of a silent 0.
     */
    @Schema(name = "UpdateShopSettings")
    public record Settings(
            @Schema(example = "6000", description = "Poisha, 0 to 500000 (৳5,000)")
            @NotNull @Min(0) @Max(ShopRules.MAX_DELIVERY_CHARGE) Long deliveryChargeInsideDhaka,

            @Schema(example = "12000", description = "Poisha, 0 to 500000 (৳5,000)")
            @NotNull @Min(0) @Max(ShopRules.MAX_DELIVERY_CHARGE) Long deliveryChargeOutsideDhaka,

            @Schema(example = "5", description = "Used for variants without their own threshold, 0 to 100000")
            @NotNull @Min(0) @Max(ShopRules.MAX_LOW_STOCK_THRESHOLD) Integer defaultLowStockThreshold,

            @Schema(example = "SD", description = "2-6 letters or digits, stored uppercase. Only affects orders created after the change")
            // @NotNull, not @NotBlank: the pattern already rejects blank, so "" gets one error, not two.
            @NotNull @Pattern(regexp = ShopRules.ORDER_NUMBER_PREFIX_REGEX, message = ShopRules.ORDER_NUMBER_PREFIX_MESSAGE)
            String orderNumberPrefix) {
    }
}
