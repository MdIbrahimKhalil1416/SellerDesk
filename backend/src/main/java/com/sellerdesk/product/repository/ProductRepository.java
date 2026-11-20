package com.sellerdesk.product.repository;

import com.sellerdesk.product.model.Product;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Simple product queries. Every method takes {@code shopId} (docs/SECURITY.md §4, layer 3).
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}: no inherited {@code findById} /
 * {@code findAll} / {@code deleteById} that would ignore the tenant.
 *
 * <p>{@link #save} exists <b>only for creating</b> a product. Later changes go through
 * {@link ProductQueryRepository}'s targeted updates, never a full save (it would overwrite stock
 * numbers that Inventory changed in the meantime).
 */
public interface ProductRepository extends Repository<Product, String> {

    Optional<Product> findByIdAndShopId(String id, String shopId);

    boolean existsByIdAndShopId(String id, String shopId);

    /** Create only. */
    Product save(Product product);
}
