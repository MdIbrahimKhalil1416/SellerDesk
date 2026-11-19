package com.sellerdesk.common.security;

import java.time.Instant;
import java.util.Set;

/**
 * The verified contents of an access token.
 *
 * @param shopId {@code null} only for PLATFORM_ADMIN
 */
public record AccessTokenClaims(
        String userId,
        String shopId,
        Role role,
        Set<Permission> permissions,
        int tokenVersion,
        Instant expiresAt) {

    public AccessTokenClaims {
        permissions = Set.copyOf(permissions);
    }
}
