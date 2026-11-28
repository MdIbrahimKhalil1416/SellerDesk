package com.sellerdesk.courier.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code sellerdesk.courier} (docs/modules/COURIER.md §2, §5, §6).
 *
 * @param webhookSecret         {@code COURIER_WEBHOOK_SECRET}: HMAC key that signs webhooks. A secret:
 *                              only from the environment. Empty is allowed in dev (every webhook is then
 *                              rejected with 401, never accepted), not in prod.
 * @param webhookSecretRequired {@code true} in prod: the app refuses to start without the secret while
 *                              a provider is enabled
 * @param jobsEnabled           runs the booking reconciler and tracking sweep on their schedule
 *                              ({@code false} in tests, which call them directly)
 * @param connectTimeout        for real providers' HTTP calls
 * @param readTimeout           for real providers' HTTP calls
 */
@Validated
@ConfigurationProperties(prefix = "sellerdesk.courier")
public record CourierProperties(
        @Pattern(regexp = "|.{32,}", message = "must be at least 32 characters (set COURIER_WEBHOOK_SECRET)")
        String webhookSecret,
        boolean webhookSecretRequired,
        boolean jobsEnabled,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull Mock mock) {

    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isEmpty();
    }

    /**
     * The mock courier (docs/modules/COURIER.md §2).
     *
     * @param enabled   offered as provider {@code MOCK}; off in prod
     * @param mode      how the mock answers by default: {@code OK}, {@code TIMEOUT}, {@code REJECT}, {@code FLAKY}
     * @param simulator dev only: moves parcels forward and sends signed webhooks to our own endpoint
     */
    public record Mock(boolean enabled, @NotNull MockMode mode, @Valid @NotNull Simulator simulator) {
    }

    /**
     * @param baseUrl       where the simulator posts webhooks, e.g. {@code http://localhost:8080}
     * @param returnPercent share of parcels that end RETURNED instead of DELIVERED
     */
    public record Simulator(boolean enabled, @NotNull Duration interval, @NotNull String baseUrl,
                            @Min(0) @Max(100) int returnPercent) {
    }

    public enum MockMode {
        /** Every call works. */
        OK,
        /** Every call throws like a timeout; a booking is still created at the "courier" (response lost). */
        TIMEOUT,
        /** Every booking is refused (invalid phone); track / cancel are refused too. */
        REJECT,
        /** Every second call times out. */
        FLAKY
    }
}
