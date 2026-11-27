package com.sellerdesk.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on Spring's {@code @Cacheable} support. The cache itself is Caffeine (in-memory).
 *
 * <p>Default settings for every cache come from application.yml ({@code spring.cache.caffeine.spec}).
 * Caches that need other settings are registered here with their own spec.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Active products per shop for the order form (docs/modules/PRODUCT.md §7). Every product
     * change evicts the shop's entry, so the 10 minutes are only a safety net.
     */
    public static final String PRODUCT_LOOKUP = "productLookup";

    /**
     * Permission list and role defaults for the staff screen (docs/modules/STAFF.md §3.2). Built
     * from enums, so it only changes with a deploy: no expiry.
     */
    public static final String STAFF_PERMISSION_CATALOG = "staffPermissionCatalog";

    /**
     * Settings per shop (delivery charges, low-stock default, order number prefix), read by other
     * modules on hot paths ({@code ShopSettingsLookup}). Every settings change evicts the shop's
     * entry, so the 10 minutes are only a safety net.
     */
    public static final String SHOP_SETTINGS = "shopSettings";

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> perCacheSettings() {
        return cacheManager -> {
            cacheManager.registerCustomCache(SHOP_SETTINGS, Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(10))
                    .maximumSize(10_000)
                    .build());
            cacheManager.registerCustomCache(PRODUCT_LOOKUP, Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(10))
                    .maximumSize(1000)
                    .build());
            cacheManager.registerCustomCache(STAFF_PERMISSION_CATALOG, Caffeine.newBuilder()
                    .maximumSize(1)
                    .build());
        };
    }
}
