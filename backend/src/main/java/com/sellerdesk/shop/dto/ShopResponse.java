package com.sellerdesk.shop.dto;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.model.ShopStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The logged-in user's shop")
public record ShopResponse(
        @Schema(example = "6745b2c3d4e5f60718293a4b") String id,
        @Schema(example = "Rahim Fashion") String name,
        @Schema(example = "01712345678", description = "Always stored as 01XXXXXXXXX") String phone,
        String email,
        AddressDto address,
        String facebookPageUrl,
        @Schema(description = "HTTPS URL of the logo, or null if the shop has none") String logoUrl,
        ShopStatus status,
        Settings settings) {

    /** Money in poisha (1 BDT = 100 poisha). */
    @Schema(name = "ShopSettings")
    public record Settings(
            @Schema(example = "6000") long deliveryChargeInsideDhaka,
            @Schema(example = "12000") long deliveryChargeOutsideDhaka,
            @Schema(example = "5") int defaultLowStockThreshold,
            @Schema(example = "SD") String orderNumberPrefix) {

        static Settings from(ShopSettings s) {
            return new Settings(s.deliveryChargeInsideDhaka(), s.deliveryChargeOutsideDhaka(),
                    s.defaultLowStockThreshold(), s.orderNumberPrefix());
        }
    }

    public static ShopResponse from(Shop shop) {
        // The logo's storage id (publicId) is internal and never leaves the server.
        String logoUrl = shop.getLogo() == null ? null : shop.getLogo().url();
        return new ShopResponse(shop.getId(), shop.getName(), shop.getPhone(), shop.getEmail(),
                AddressDto.from(shop.getAddress()), shop.getFacebookPageUrl(), logoUrl, shop.getStatus(),
                Settings.from(shop.getSettings()));
    }
}
