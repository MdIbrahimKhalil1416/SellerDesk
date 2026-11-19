package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterShopResponse(
        @Schema(example = "Registration successful. We sent a 6-digit code to your email.") String message,
        @Schema(example = "rahim@example.com") String email,
        @Schema(example = "60", description = "Seconds until a new code may be requested") long resendAfterSeconds) {
}
