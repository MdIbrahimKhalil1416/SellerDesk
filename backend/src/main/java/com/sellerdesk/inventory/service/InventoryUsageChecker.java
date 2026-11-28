package com.sellerdesk.inventory.service;

import com.sellerdesk.inventory.repository.StockMovementRepository;
import com.sellerdesk.product.service.ProductUsageChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers the product module's "is this product used?" (docs/modules/PRODUCT.md §5): yes, as
 * soon as any stock movement exists for it. Then the product can only be archived, not deleted,
 * so the movement log never points at a missing product.
 */
@Component
@RequiredArgsConstructor
public class InventoryUsageChecker implements ProductUsageChecker {

    private final StockMovementRepository movementRepository;

    @Override
    public boolean isUsed(String shopId, String productId) {
        return movementRepository.existsByShopIdAndProductId(shopId, productId);
    }
}
