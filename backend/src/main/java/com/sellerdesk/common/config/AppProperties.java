package com.sellerdesk.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @Valid @NotNull Jwt jwt) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String secret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {
    }
}
