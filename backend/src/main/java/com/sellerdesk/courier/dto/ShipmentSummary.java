package com.sellerdesk.courier.dto;

import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.ShipmentStatus;
import java.time.Instant;

/** A list row: the order page's shipment history and {@code GET /shipments}. */
public record ShipmentSummary(String id, String orderId, String orderNumber, CourierCode provider,
                              String consignmentId, String trackingCode, ShipmentStatus status, long codAmount,
                              Long collectedAmount, boolean codMismatch, String failureReason,
                              boolean needsManualCheck, Instant createdAt, Instant updatedAt) {
}
