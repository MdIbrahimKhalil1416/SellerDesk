package com.sellerdesk.courier.dto;

import com.sellerdesk.common.model.Address;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.EventSource;
import com.sellerdesk.courier.model.ShipmentStatus;
import java.time.Instant;
import java.util.List;

/**
 * One shipment (docs/modules/COURIER.md §4). Money in poisha. The internal {@code eventKey} is
 * never returned.
 *
 * @param allowedActions what the current user can do now ({@code TRACK}, {@code CANCEL}); the
 *                       backend still checks everything
 */
public record ShipmentResponse(String id, String orderId, String orderNumber, CourierCode provider,
                               String consignmentId, String trackingCode, ShipmentStatus status,
                               long codAmount, Long collectedAmount, boolean codMismatch, Long courierFee,
                               Recipient recipient, String note, String failureReason, boolean needsManualCheck,
                               String cancelReason, List<Event> events, List<Action> allowedActions,
                               Actor createdBy, Instant createdAt, Instant updatedAt) {

    public record Recipient(String name, String phone, Address address) {
    }

    public record Event(Instant at, EventSource source, ShipmentStatus status, String providerStatus,
                        boolean ignored, String note) {
    }

    public record Actor(String id, String name) {
    }

    public enum Action {
        TRACK,
        CANCEL
    }
}
