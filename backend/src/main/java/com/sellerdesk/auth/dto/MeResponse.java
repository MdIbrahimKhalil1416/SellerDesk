package com.sellerdesk.auth.dto;

import com.sellerdesk.shop.dto.ShopSummary;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Who am I? Used by the frontend after login or page reload to show the user's name, shop and
 * which menu items their permissions allow.
 *
 * @param shop {@code null} for PLATFORM_ADMIN (no shop)
 */
public record MeResponse(
        UserResponse user,
        @Schema(nullable = true) ShopSummary shop) {
}
