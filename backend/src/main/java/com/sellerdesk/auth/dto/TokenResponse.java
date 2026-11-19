package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Result of {@code /auth/refresh}. A new refresh cookie comes with it. */
public record TokenResponse(
        String accessToken,
        @Schema(example = "900") long expiresIn) {
}
