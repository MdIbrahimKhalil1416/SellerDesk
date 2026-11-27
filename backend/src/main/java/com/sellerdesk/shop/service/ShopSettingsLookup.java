package com.sellerdesk.shop.service;

import com.sellerdesk.common.config.CacheConfig;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Cached read of a shop's settings. Other modules call this often (every new order needs the
 * delivery charge and order number prefix, the inventory list needs the low-stock threshold), so
 * it should not cost a database query each time.
 *
 * <p>How it stays correct: every settings change calls {@link #evict} right after the write, so
 * the next read loads the new values. The cache also expires entries after 10 minutes, as a
 * safety net (e.g. a change made directly in the database).
 *
 * <p>Its own bean, not methods on {@code ShopService}: Spring's {@code @Cacheable} works through a
 * proxy around the bean, and a call from inside the same class would skip the proxy (no cache).
 */
@Component
@RequiredArgsConstructor
public class ShopSettingsLookup {

    private final ShopRepository shopRepository;

    /**
     * The settings of the given shop. {@code shopId} must come from the logged-in user
     * ({@code CurrentUser.shopId()}), never from the request.
     *
     * @throws ResourceNotFoundException if the shop does not exist (not cached)
     */
    @Cacheable(cacheNames = CacheConfig.SHOP_SETTINGS, key = "#shopId")
    public ShopSettings forShop(String shopId) {
        return shopRepository.findById(shopId)
                .map(Shop::getSettings)
                .orElseThrow(() -> ResourceNotFoundException.of("Shop"));
    }

    /** Drops the cached settings of one shop. Called after every settings change. */
    @CacheEvict(cacheNames = CacheConfig.SHOP_SETTINGS, key = "#shopId")
    public void evict(String shopId) {
        // Body intentionally empty: the annotation does the work.
    }
}
