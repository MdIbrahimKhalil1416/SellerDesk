package com.sellerdesk.shop.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.shop.dto.ShopResponse;
import com.sellerdesk.shop.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "My shop". There is no {@code /shops/{id}}: the shop is always the logged-in user's shop,
 * so no id from another tenant can ever be asked for (docs/ARCHITECTURE.md §4).
 */
@Tag(name = "Shop", description = "The logged-in user's shop")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    @Operation(summary = "Get my shop")
    @ApiResponse(responseCode = "200", description = "The shop")
    @ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "User has no shop (platform admin)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ShopResponse getMyShop() {
        return shopService.getMyShop();
    }
}
