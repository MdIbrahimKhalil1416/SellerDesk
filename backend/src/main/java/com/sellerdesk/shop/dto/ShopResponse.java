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
        @Schema(example = "01712345678") String phone,
        String email,
        AddressDto address,
        String facebookPageUrl,
        ShopStatus status,
        Settings settings) {

    /** Money in poisha (1 BDT = 100 poisha). */
    public record Settings(
            @Schema(example = "6000") long deliveryChargeInsideDhaka,
            @Schema(example = "12000") long deliveryChargeOutsideDhaka,
            @Schema(example = "5") int defaultLowStockThreshold) {

        static Settings from(ShopSettings s) {
            return new Settings(s.deliveryChargeInsideDhaka(), s.deliveryChargeOutsideDhaka(),
                    s.defaultLowStockThreshold());
        }
    }

    public static ShopResponse from(Shop shop) {
        return new ShopResponse(shop.getId(), shop.getName(), shop.getPhone(), shop.getEmail(),
                AddressDto.from(shop.getAddress()), shop.getFacebookPageUrl(), shop.getStatus(),
                Settings.from(shop.getSettings()));
    }
}
