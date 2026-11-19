package com.sellerdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Successful login / email verification. The refresh token is <b>not</b> here: it is sent as an
 * HttpOnly cookie that JavaScript cannot read (docs/SECURITY.md §2).
 */
public record AuthResponse(
        @Schema(description = "JWT for the Authorization: Bearer header. Keep it in memory only.") String accessToken,
        @Schema(example = "900", description = "Seconds until the access token expires") long expiresIn,
        UserResponse user) {
}
