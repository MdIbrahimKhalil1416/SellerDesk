package com.sellerdesk.courier.dto;

import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.ShipmentStatus;
import java.util.List;

/** One result per order; a failure does not stop the others. {@code BOOKING} counts as ok. */
public record BulkShipmentResponse(CourierCode provider, int succeeded, int failed, List<Result> results) {

    public record Result(String orderId, String orderNumber, boolean ok, String shipmentId, ShipmentStatus status,
                         String errorCode, String message) {
    }
}
