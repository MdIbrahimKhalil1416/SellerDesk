package com.sellerdesk.product.service;

import com.sellerdesk.common.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops a shop's cached product lookup whenever one of its products changes
 * (docs/modules/PRODUCT.md §7).
 *
 * <ul>
 *   <li>{@code AFTER_COMMIT}: a write that is rolled back must not evict, otherwise another
 *       request could refill the cache with data that is about to change back.</li>
 *   <li>{@code fallbackExecution = true}: most product writes run without a transaction; the
 *       listener must run for them too (immediately).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProductLookupCacheEvictor {

    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductChangedEvent event) {
        Cache cache = cacheManager.getCache(CacheConfig.PRODUCT_LOOKUP);
        if (cache != null) {
            cache.evict(event.shopId());
        }
    }
}
