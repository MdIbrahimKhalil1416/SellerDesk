package com.sellerdesk.shop.dto;

import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Short shop info for {@code /auth/me}. The full shop is {@code GET /shop}. */
public record ShopSummary(
        @Schema(example = "6745b2c3d4e5f60718293a4b") String id,
        @Schema(example = "Rahim Fashion") String name,
        ShopStatus status) {

    public static ShopSummary from(Shop shop) {
        return new ShopSummary(shop.getId(), shop.getName(), shop.getStatus());
    }
}
