package com.sellerdesk.courier.provider;

import java.util.Map;

/**
 * Outcome of a booking call. The difference between {@link Rejected} and {@link Unavailable} is
 * the point of this type (docs/modules/COURIER.md §6):
 * <ul>
 *   <li>{@link Rejected}: the courier gave a definite "no" → the shipment can safely be FAILED.</li>
 *   <li>{@link Unavailable}: timeout, 5xx, network error → we do <b>not</b> know whether the parcel
 *       exists → the shipment stays BOOKING and is retried with the same {@code merchantRef}.</li>
 * </ul>
 */
public sealed interface BookingResult {

    record Created(String consignmentId, String trackingCode) implements BookingResult {
    }

    /** @param fieldHints our field name (e.g. {@code recipient.phone}) → problem, where the adapter can map it */
    record Rejected(String reason, Map<String, String> fieldHints) implements BookingResult {
    }

    record Unavailable(String cause) implements BookingResult {
    }
}
