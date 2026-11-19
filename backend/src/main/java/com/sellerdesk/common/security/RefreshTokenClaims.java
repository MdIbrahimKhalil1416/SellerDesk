package com.sellerdesk.common.security;

import java.time.Instant;

/**
 * The verified contents of a refresh token. No role or permissions on purpose: they are re-read
 * from the database on every refresh, so changes are picked up (docs/SECURITY.md §2).
 */
public record RefreshTokenClaims(String userId, int tokenVersion, Instant expiresAt) {
}
