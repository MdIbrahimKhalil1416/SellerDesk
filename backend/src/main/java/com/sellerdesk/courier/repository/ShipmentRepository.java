package com.sellerdesk.courier.repository;

import com.sellerdesk.courier.model.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Simple shipment queries. Every method takes {@code shopId} (docs/SECURITY.md §4, layer 3).
 * {@link #insert} is only for creating; later changes are conditional updates in
 * {@link ShipmentQueryRepository}.
 */
public interface ShipmentRepository extends Repository<Shipment, String> {

    Optional<Shipment> findByIdAndShopId(String id, String shopId);

    /** All bookings of an order, newest first. */
    List<Shipment> findByShopIdAndOrderIdOrderByCreatedAtDesc(String shopId, String orderId);

    /** The active booking of an order (at most one, unique index). */
    Optional<Shipment> findByShopIdAndOrderIdAndActiveTrue(String shopId, String orderId);

    boolean existsByShopIdAndOrderIdAndActiveTrue(String shopId, String orderId);

    /** Fails with {@code DuplicateKeyException} if the order already has an active shipment. */
    Shipment insert(Shipment shipment);
}
