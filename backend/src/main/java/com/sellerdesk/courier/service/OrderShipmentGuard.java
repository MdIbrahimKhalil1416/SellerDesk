package com.sellerdesk.courier.service;

import com.sellerdesk.courier.repository.ShipmentRepository;
import com.sellerdesk.order.service.ShipmentGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Tells the order module whether an order is booked with a courier right now. */
@Component
@RequiredArgsConstructor
public class OrderShipmentGuard implements ShipmentGuard {

    private final ShipmentRepository repository;

    @Override
    public boolean hasActiveShipment(String shopId, String orderId) {
        return repository.existsByShopIdAndOrderIdAndActiveTrue(shopId, orderId);
    }
}
