package com.sellerdesk.order.service;

import com.sellerdesk.order.repository.OrderRepository;
import com.sellerdesk.product.service.ProductUsageChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Answers the product module's "is this product used?" (docs/modules/PRODUCT.md §5): yes, as soon
 * as any order of the shop has an item of it. Then the product can only be archived, not deleted,
 * so old orders never point at a missing product.
 */
@Component
@RequiredArgsConstructor
public class OrderUsageChecker implements ProductUsageChecker {

    private final OrderRepository orderRepository;

    @Override
    public boolean isUsed(String shopId, String productId) {
        return orderRepository.existsByShopIdAndItemsProductId(shopId, productId);
    }
}
