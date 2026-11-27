package com.sellerdesk.shop.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.shop.dto.ShopResponse;
import com.sellerdesk.shop.dto.UpdateShopRequest;
import com.sellerdesk.shop.service.ShopLogoService;
import com.sellerdesk.shop.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * "My shop". There is no {@code /shops/{id}}: the shop is always the logged-in user's shop,
 * so no id from another tenant can ever be asked for (docs/ARCHITECTURE.md §4).
 */
@Tag(name = "Shop", description = "The logged-in user's shop: profile, logo and settings")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/shop")
@RequiredArgsConstructor
public class ShopController {

    static final String SETTINGS = "hasAuthority('SHOP_SETTINGS')";

    private final ShopService shopService;
    private final ShopLogoService logoService;

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

    @Operation(summary = "Update profile and settings",
            description = "Replaces name, phone, email, address, Facebook page and settings (every field is sent; "
                    + "a missing optional field is cleared). Phone is stored as 01XXXXXXXXX, the order number prefix "
                    + "in uppercase. A new prefix only affects orders created afterwards.")
    @ApiResponse(responseCode = "200", description = "Updated shop")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (see fieldErrors)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing SHOP_SETTINGS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping
    @PreAuthorize(SETTINGS)
    public ShopResponse update(@Valid @RequestBody UpdateShopRequest request) {
        return shopService.update(request);
    }

    @Operation(summary = "Upload or replace the logo",
            description = "One file in the multipart field `file`. Same rules as product images: JPEG, PNG or WebP "
                    + "(checked by the file's bytes), max 5 MB. The old logo file is deleted.")
    @ApiResponse(responseCode = "200", description = "Logo set; the whole shop is returned")
    @ApiResponse(responseCode = "400", description = "IMAGE_TYPE_NOT_ALLOWED (not JPEG/PNG/WebP, or empty)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing SHOP_SETTINGS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "IMAGE_TOO_LARGE (over 5 MB)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "IMAGE_UPLOAD_FAILED (Cloudinary failed; try again)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(SETTINGS)
    public ShopResponse uploadLogo(
            @Parameter(description = "JPEG, PNG or WebP, max 5 MB",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestPart("file") MultipartFile file) {
        return logoService.upload(file);
    }

    @Operation(summary = "Remove the logo", description = "The file is also deleted from Cloudinary. No logo: no change.")
    @ApiResponse(responseCode = "200", description = "Logo removed; the whole shop is returned")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing SHOP_SETTINGS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/logo")
    @PreAuthorize(SETTINGS)
    public ShopResponse deleteLogo() {
        return logoService.delete();
    }
}
