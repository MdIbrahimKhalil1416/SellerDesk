package com.sellerdesk.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe access to our own settings under the {@code sellerdesk.*} prefix in application.yml.
 *
 * <p>{@code @Validated} makes the app fail at startup with a clear message if a value is
 * missing or invalid, instead of failing later on the first request that needs it.
 */
@Validated
@ConfigurationProperties(prefix = "sellerdesk")
public record AppProperties(
        /*
         * The URL the browser uses for SellerDesk (APP_URL), e.g. https://sellerdesk.onrender.com.
         * Frontend and API share this one origin. The refresh endpoint rejects any other Origin.
         *
         * Must look like a browser Origin: scheme + host (+ port), no path, no trailing slash.
         * If APP_URL is not set, Spring keeps the text "${APP_URL}" as the value. Without this
         * check the app would start and then reject every refresh. With it, startup fails and
         * names the missing setting.
         */
        @NotBlank
        @Pattern(regexp = "https?://[^/\\s]+",
                message = "must be the app's origin, e.g. https://sellerdesk.onrender.com (set APP_URL; no trailing slash)")
        String appUrl,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Mail mail,
        @Valid @NotNull RefreshCookie refreshCookie) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String secret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {
    }

    /** @param from sender address, e.g. {@code SellerDesk <no-reply@sellerdesk.app>} */
    public record Mail(@NotBlank String from) {
    }

    /**
     * Attributes of the refresh-token cookie (docs/SECURITY.md §2).
     *
     * <p>Frontend and API are on the same origin, so the cookie is always first-party.
     * {@code SameSite=None} is not allowed: it would let other sites send the cookie.
     *
     * @param sameSite {@code Strict} or {@code Lax}
     * @param secure   send the cookie over HTTPS only
     */
    public record RefreshCookie(
            @NotBlank @Pattern(regexp = "Strict|Lax", message = "must be Strict or Lax") String sameSite,
            boolean secure) {
    }
}
