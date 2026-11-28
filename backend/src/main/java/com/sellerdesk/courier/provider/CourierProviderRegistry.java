package com.sellerdesk.courier.provider;

import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.courier.model.CourierCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The enabled couriers, by code. Which ones exist is decided by configuration: a provider bean
 * is only created when {@code sellerdesk.courier.<name>.enabled = true}.
 *
 * <p>Checked at startup: with an enabled provider, prod refuses to start without
 * {@code COURIER_WEBHOOK_SECRET}; dev only warns (webhooks are then always rejected, never accepted).
 */
@Slf4j
@Component
public class CourierProviderRegistry {

    public static final String COURIER_NOT_AVAILABLE = "COURIER_NOT_AVAILABLE";

    private final Map<CourierCode, CourierProvider> providers = new EnumMap<>(CourierCode.class);

    public CourierProviderRegistry(List<CourierProvider> beans, CourierProperties properties) {
        for (CourierProvider provider : beans) {
            if (providers.put(provider.code(), provider) != null) {
                throw new IllegalStateException("Two CourierProvider beans for " + provider.code());
            }
        }
        if (!providers.isEmpty() && !properties.hasWebhookSecret()) {
            if (properties.webhookSecretRequired()) {
                throw new IllegalStateException("COURIER_WEBHOOK_SECRET is required while a courier is enabled "
                        + providers.keySet() + ". Generate one with: openssl rand -base64 48");
            }
            log.warn("COURIER_WEBHOOK_SECRET is not set: every courier webhook will be rejected with 401");
        }
        log.info("Courier providers enabled: {}", providers.keySet());
    }

    /** @throws BusinessRuleException {@code 400 COURIER_NOT_AVAILABLE} if unknown or disabled */
    public CourierProvider get(CourierCode code) {
        CourierProvider provider = code == null ? null : providers.get(code);
        if (provider == null) {
            throw new BusinessRuleException(HttpStatus.BAD_REQUEST, COURIER_NOT_AVAILABLE,
                    "This courier is not available. Available: " + providers.keySet());
        }
        return provider;
    }

    public Set<CourierCode> enabled() {
        return Set.copyOf(providers.keySet());
    }
}
