package com.sellerdesk.auth.controller;

import com.sellerdesk.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the refresh-token cookie (docs/SECURITY.md §2).
 *
 * <ul>
 *   <li>{@code HttpOnly}: JavaScript cannot read it, so an XSS bug cannot steal it.</li>
 *   <li>{@code Path=/api/v1/auth}: the browser only sends it to auth endpoints.</li>
 *   <li>{@code SameSite} / {@code Secure}: from config, different in dev and prod.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    public static final String NAME = "sd_refresh";
    static final String PATH = "/api/v1/auth";

    private final AppProperties appProperties;

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken).maxAge(appProperties.jwt().refreshTokenTtl()).build();
    }

    /**
     * Deletes the cookie in the browser: same name, path and attributes, empty value, Max-Age=0.
     * A cookie is only replaced if name and path match the original exactly.
     */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        AppProperties.RefreshCookie config = appProperties.refreshCookie();
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite(config.sameSite())
                .path(PATH);
    }
}
