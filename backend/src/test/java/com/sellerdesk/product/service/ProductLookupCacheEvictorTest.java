package com.sellerdesk.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerdesk.common.config.CacheConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class ProductLookupCacheEvictorTest {

    @Test
    void evictsOnlyTheChangedShop() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheConfig.PRODUCT_LOOKUP);
        var cache = cacheManager.getCache(CacheConfig.PRODUCT_LOOKUP);
        cache.put("shopA", List.of());
        cache.put("shopB", List.of());

        new ProductLookupCacheEvictor(cacheManager).onProductChanged(new ProductChangedEvent("shopA", "p1"));

        assertThat(cache.get("shopA")).isNull();
        assertThat(cache.get("shopB")).isNotNull();
    }
}
