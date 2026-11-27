package com.sellerdesk.shop.repository;

import com.sellerdesk.shop.model.Shop;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * The shop document's {@code _id} is the shopId, so "find by id" here is already a tenant-scoped
 * query: callers pass the shopId from TenantContext, never an id from the request.
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}: no {@code findAll()} that would
 * list every tenant.
 */
public interface ShopRepository extends Repository<Shop, String> {

    Optional<Shop> findById(String shopId);

    boolean existsById(String shopId);

    Shop save(Shop shop);
}
