package com.sellerdesk.inventory.repository;

import com.sellerdesk.inventory.model.StockMovement;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Simple stock-movement queries. Every method takes {@code shopId} (docs/SECURITY.md §4, layer 3).
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}: no inherited {@code findAll} /
 * {@code deleteById} that would ignore the tenant, and no {@code save} of an existing movement:
 * the log is append-only, so the only write is {@link #insert}.
 *
 * <p>{@code shopId}, {@code variantId} and {@code productId} are stored as ObjectIds; Spring
 * converts the String parameters because the entity fields are annotated with
 * {@code @Field(targetType = OBJECT_ID)}.
 */
public interface StockMovementRepository extends Repository<StockMovement, String> {

    /** Append a new movement. */
    StockMovement insert(StockMovement movement);

    Optional<StockMovement> findByShopIdAndRequestId(String shopId, String requestId);

    boolean existsByShopIdAndVariantId(String shopId, String variantId);

    boolean existsByShopIdAndProductId(String shopId, String productId);
}
