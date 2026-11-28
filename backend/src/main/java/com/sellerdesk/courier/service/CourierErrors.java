package com.sellerdesk.courier.service;

import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.courier.model.CourierCode;
import com.sellerdesk.courier.model.ShipmentStatus;
import com.sellerdesk.order.model.OrderStatus;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Error codes of the Courier module (docs/modules/COURIER.md §4). The frontend switches on them. */
public final class CourierErrors {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ORDER_NOT_PACKED = "ORDER_NOT_PACKED";
    public static final String SHIPMENT_ALREADY_ACTIVE = "SHIPMENT_ALREADY_ACTIVE";
    public static final String SHIPMENT_NOT_CANCELLABLE = "SHIPMENT_NOT_CANCELLABLE";
    public static final String COURIER_REJECTED = "COURIER_REJECTED";
    public static final String COURIER_UNAVAILABLE = "COURIER_UNAVAILABLE";

    private CourierErrors() {
    }

    /** Shipment (or webhook route) unknown <b>or of another shop</b>: the two look the same. */
    public static ResourceNotFoundException shipmentNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, "Shipment not found");
    }

    public static BusinessRuleException orderNotPacked(OrderStatus current) {
        return new BusinessRuleException(ORDER_NOT_PACKED,
                "Only PACKED orders can be sent to a courier (this one is " + current + ")");
    }

    public static BusinessRuleException alreadyActive(CourierCode existing) {
        return new BusinessRuleException(SHIPMENT_ALREADY_ACTIVE,
                "The order is already booked with " + existing.displayName() + ". Cancel that shipment first");
    }

    public static BusinessRuleException notCancellable(ShipmentStatus current, String detail) {
        return new BusinessRuleException(SHIPMENT_NOT_CANCELLABLE, detail != null ? detail
                : "A shipment can only be cancelled before pickup (it is " + current + ")");
    }

    /** {@code 409 COURIER_REJECTED} with the courier's reason, and field errors where the adapter knows them. */
    public static AppException rejected(String reason, Map<String, String> fieldHints) {
        List<ApiError.FieldError> errors = fieldHints == null ? List.of()
                : fieldHints.entrySet().stream().map(e -> new ApiError.FieldError(e.getKey(), e.getValue())).toList();
        return new CourierConflict(COURIER_REJECTED, "The courier refused the booking: " + reason, errors);
    }

    public static BusinessRuleException unavailable() {
        return new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, COURIER_UNAVAILABLE,
                "The courier did not answer. Nothing was changed; try again in a moment");
    }

    static final class CourierConflict extends AppException {
        CourierConflict(String code, String message, List<ApiError.FieldError> fieldErrors) {
            super(HttpStatus.CONFLICT, code, message, fieldErrors);
        }
    }
}
