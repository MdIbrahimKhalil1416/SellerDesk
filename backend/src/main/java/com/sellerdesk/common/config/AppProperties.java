package com.sellerdesk.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
        @NotBlank String frontendUrl,
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
     * @param sameSite {@code Strict}, {@code Lax} or {@code None}
     * @param secure   send the cookie over HTTPS only
     */
    public record RefreshCookie(
            @NotBlank @Pattern(regexp = "Strict|Lax|None", message = "must be Strict, Lax or None") String sameSite,
            boolean secure) {

        /** Browsers silently drop {@code SameSite=None} cookies that are not {@code Secure}. */
        @AssertTrue(message = "secure must be true when sameSite is None")
        public boolean isSameSiteNoneSecure() {
            return !"None".equals(sameSite) || secure;
        }
    }
}
