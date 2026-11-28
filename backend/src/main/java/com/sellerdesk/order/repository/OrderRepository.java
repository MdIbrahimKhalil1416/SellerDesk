package com.sellerdesk.order.repository;

import com.sellerdesk.order.model.Order;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Simple order queries. Every method takes {@code shopId} (docs/SECURITY.md §4, layer 3).
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}: no inherited {@code findById} /
 * {@code findAll} / {@code deleteById} that would ignore the tenant, and no delete at all (orders
 * are cancelled, never deleted). {@link #insert} is <b>only for creating</b>; later changes are
 * targeted updates in {@link OrderQueryRepository}.
 */
public interface OrderRepository extends Repository<Order, String> {

    Optional<Order> findByIdAndShopId(String id, String shopId);

    Optional<Order> findByShopIdAndRequestId(String shopId, String requestId);

    /** "Is this product used by any order?" ({@code OrderUsageChecker}). */
    boolean existsByShopIdAndItemsProductId(String shopId, String productId);

    /** Create only. Fails with {@code DuplicateKeyException} on a repeated number or requestId. */
    Order insert(Order order);
}
